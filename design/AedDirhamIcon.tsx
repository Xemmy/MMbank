import * as React from "react";

/**
 * UAE Dirham (AED) currency symbol — the official Central Bank letterform:
 * a "D" with two horizontal strike bars, hand-crafted as filled paths.
 *
 * Reference web implementation for any future web surface of MMbank; the
 * Android app renders the identical path data via a Compose ImageVector
 * (app/.../ui/theme/CurrencyGlyphs.kt). Keep the two in sync.
 *
 * Default mode inherits `currentColor`, so it sits inline with text and
 * Tailwind color utilities (`className="text-emerald-400"`) exactly like a
 * character. `uaeColors` switches to the national palette on a white badge.
 */
export interface AedDirhamIconProps extends React.SVGProps<SVGSVGElement> {
  /** Square bounding-box size. Any CSS size; defaults to "1em" so it scales with the surrounding font. */
  size?: number | string;
  /** Render the UAE national-palette badge variant instead of currentColor. */
  uaeColors?: boolean;
}

const D_PATH =
  "M3.4 0.4 H9 C14.85 0.4 19.6 5.59 19.6 12 C19.6 18.41 14.85 23.6 9 23.6 H3.4 Z " +
  "M6 3 H9 C13.42 3 17 7.03 17 12 C17 16.97 13.42 21 9 21 H6 Z";
const BAR_TOP = "M0 7.55 H16.1 V9.75 H0 Z";
const BAR_BOTTOM = "M0 14.25 H16.1 V16.45 H0 Z";

export const AedDirhamIcon = React.forwardRef<SVGSVGElement, AedDirhamIconProps>(
  ({ size = "1em", uaeColors = false, ...props }, ref) => (
    <svg
      ref={ref}
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      role="img"
      aria-label="UAE Dirham"
      {...props}
    >
      {uaeColors ? (
        <>
          <rect width={24} height={24} rx={5.5} fill="#FFFFFF" />
          <g transform="translate(3.8 2.16) scale(0.82)">
            <path fillRule="evenodd" fill="#000000" d={D_PATH} />
            <path fill="#00732F" d={BAR_TOP} />
            <path fill="#FF0000" d={BAR_BOTTOM} />
          </g>
        </>
      ) : (
        <g transform="translate(2 0)" fill="currentColor">
          <path fillRule="evenodd" d={D_PATH} />
          <path d={BAR_TOP} />
          <path d={BAR_BOTTOM} />
        </g>
      )}
    </svg>
  ),
);
AedDirhamIcon.displayName = "AedDirhamIcon";

export default AedDirhamIcon;
