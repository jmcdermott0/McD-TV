Captions default test files (emulator QA step 4c). Made with ffmpeg on 2026-11-11. Each subtitle cue runs the
whole file and names its own track, so a screenshot shows which track is on.

- `qa-multi.mkv`: 120 s, four text tracks. Spanish (marked default in the file), English Forced (forced flag),
  English SDH (hearing impaired flag), English. Jarvis must show English, not the file's Spanish default.
- `qa-no-english.mkv`: 120 s, Spanish (marked default) and French. Jarvis must say there are no English captions.
- `qa-addon-en.srt`: an English subtitle file, sent as an addon subtitle with `qa-no-english.mkv`. Jarvis must pick it.

Rebuild (spa.srt and the others hold one cue, 0:00 to 2:00, with the text the screenshot shows):

    V="color=c=0x203040:size=64x36:rate=1"
    ffmpeg -f lavfi -i "$V" -i spa.srt -i engf.srt -i engsdh.srt -i eng.srt -map 0:v -map 1 -map 2 -map 3 -map 4 -t 120 \
      -c:v libx264 -preset veryslow -crf 40 -g 30 -pix_fmt yuv420p -c:s srt \
      -metadata:s:s:0 language=spa -metadata:s:s:0 title=Spanish -disposition:s:0 default \
      -metadata:s:s:1 language=eng -metadata:s:s:1 title="English Forced" -disposition:s:1 forced \
      -metadata:s:s:2 language=eng -metadata:s:s:2 title="English SDH" -disposition:s:2 hearing_impaired \
      -metadata:s:s:3 language=eng -metadata:s:s:3 title=English -disposition:s:3 0 qa-multi.mkv
    ffmpeg -f lavfi -i "$V" -i spa.srt -i fre.srt -map 0:v -map 1 -map 2 -t 120 \
      -c:v libx264 -preset veryslow -crf 40 -g 30 -pix_fmt yuv420p -c:s srt \
      -metadata:s:s:0 language=spa -metadata:s:s:0 title=Spanish -disposition:s:0 default \
      -metadata:s:s:1 language=fre -metadata:s:s:1 title=French -disposition:s:1 0 qa-no-english.mkv
