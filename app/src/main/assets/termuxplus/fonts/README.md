Bundled terminal font
=====================

Default TermuxPlus terminal font:

- JetBrainsMonoNerdFontMono-Regular.ttf
- Source: Nerd Fonts v3.4.0 JetBrainsMono release asset
- Upstream: https://github.com/ryanoasis/nerd-fonts
- License: SIL Open Font License 1.1, see OFL.txt in this directory.

The app uses this font only when the user has not provided
`~/.termux/font.ttf`. User and Termux:Styling custom fonts remain higher
priority than this bundled default.


Symbol fallback font
====================

- TermuxPlusSymbolsFallback-Regular.ttf
- Source: a subset of Noto Sans Symbols 2 (Google Noto). See
  NotoSansSymbols2-OFL.txt in this directory.
- License: SIL Open Font License 1.1.
- Contents: exactly the codepoints that Noto Sans Symbols 2 covers but the
  primary JetBrains Mono font lacks -- i.e. the set difference of the two
  cmaps (2506 codepoints). It is NOT a hand-picked block list; it is computed
  programmatically so nothing the primary font is missing gets dropped.

Regenerate (requires fonttools): dump each font's covered codepoints, write the
diff to diff_unicodes.txt, then subset Noto Sans Symbols 2 down to that diff:

    pyftsubset NotoSansSymbols2-Regular.ttf \
      --unicodes-file=diff_unicodes.txt \
      --output-file=TermuxPlusSymbolsFallback-Regular.ttf \
      --no-hinting --desubroutinize --drop-tables+=GSUB,GPOS,GDEF

The GPU renderer (TerminalGpuRenderer) uses this only as a fallback: when a
glyph is missing from the primary font, it is tried before the system typeface.
This restores non-emoji symbols (e.g. U+23F5 ⏵, codex's prompt) that no Nerd
Font ships and that some ROMs also strip from the system font chain.
