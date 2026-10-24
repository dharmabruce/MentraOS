import type {DisplayProfile} from "./types"

/** Monospace advance (0.6 em) at the 18 px size the native GO3 renderer draws, rounded up. */
const GO3_GLYPH_WIDTH_PX = 11

/**
 * INMO GO3 (prototype). The phone renders text into a 640×480 PNG of which rows 60–479 are visible,
 * so the profile is whatever the native renderer (`sgcs/inmo/Go3Canvas.java`) draws: 18 px monospace,
 * 22 px lines, 16 px side margins and 12 px top and bottom margins. Monospace keeps this wrapping and
 * the native drawing in exact agreement.
 */
export const GO3_PROFILE: DisplayProfile = {
  id: "inmo-go3",
  name: "INMO GO3",
  displayWidthPx: 640 - 2 * 16,
  displayHeightPx: 420 - 2 * 12,
  maxLines: 18,
  lineHeightPx: 22,

  // Frames go over GATT as PNG images; text never travels as text, so these are generous ceilings.
  maxPayloadBytes: 8192,
  bleChunkSize: 230,

  fontMetrics: {
    glyphWidths: new Map(
      Array.from({length: 95}, (_, index): [string, number] => [String.fromCharCode(0x20 + index), GO3_GLYPH_WIDTH_PX]),
    ),
    // Full-width scripts fall back to a 1 em glyph.
    defaultGlyphWidth: 18,
    renderFormula: (glyphWidth: number) => glyphWidth,
    uniformScripts: {
      cjk: 18,
      hiragana: 18,
      katakana: 18,
      korean: 18,
      cyrillic: GO3_GLYPH_WIDTH_PX,
    },
    fallback: {
      latinMaxWidth: GO3_GLYPH_WIDTH_PX,
      unknownBehavior: "useLatinMax",
    },
  },
}
