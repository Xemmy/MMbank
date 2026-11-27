package com.xemmy.mmbank.data.repository

import androidx.room.withTransaction
import com.xemmy.mmbank.data.local.AppDatabase
import com.xemmy.mmbank.data.local.entity.LabelEntity
import com.xemmy.mmbank.data.local.entity.TransactionLabelEntity
import com.xemmy.mmbank.data.local.entity.toDomain
import com.xemmy.mmbank.domain.label.LabelLink
import com.xemmy.mmbank.domain.label.LabelRules
import com.xemmy.mmbank.domain.model.Label
import com.xemmy.mmbank.domain.repository.LabelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class LabelRepositoryImpl(private val database: AppDatabase) : LabelRepository {

    private val dao get() = database.labelDao()

    override fun observeAll(): Flow<List<Label>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeLinks(): Flow<List<LabelLink>> =
        dao.observeLinks().map { list -> list.map { LabelLink(it.transactionId, it.labelId) } }

    override suspend fun create(name: String): Label? {
        val clean = LabelRules.normalize(name) ?: return null
        dao.findByName(clean)?.let { return it.toDomain() }
        val color = LabelRules.nextColor(dao.observeAll().first().size)
        val id = dao.insert(LabelEntity(name = clean, colorHex = color))
        return if (id > 0) Label(id, clean, color) else dao.findByName(clean)?.toDomain()
    }

    override suspend fun rename(id: Long, name: String): Boolean {
        val clean = LabelRules.normalize(name) ?: return false
        val all = dao.observeAll().first().map { it.toDomain() }
        if (LabelRules.isDuplicate(all, clean, ignoreId = id)) return false
        val current = all.firstOrNull { it.id == id } ?: return false
        dao.update(LabelEntity(id, clean, current.colorHex))
        return true
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun merge(from: Long, to: Long) {
        if (from == to) return
        database.withTransaction {
            val links = dao.getAllLinks().map { LabelLink(it.transactionId, it.labelId) }
            dao.insertLinks(LabelRules.mergeLinksToAdd(links, from, to).map { TransactionLabelEntity(it.transactionId, it.labelId) })
            dao.delete(from)
        }
    }

    override suspend fun setLabels(transactionId: Long, labelIds: Set<Long>) {
        database.withTransaction {
            dao.clearLinksOf(transactionId)
            dao.insertLinks(labelIds.map { TransactionLabelEntity(transactionId, it) })
        }
    }
}
