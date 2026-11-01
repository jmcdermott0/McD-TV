Play Next at credits test files (PlayNextTest, emulator QA step 4n). Real containers, made on 2026-11-01.
The four `mkvmerge-*.mkv` files are the first 8 KB of the real files (header, tracks, chapters and the start of the
first cluster), the same kind of cut the player's HeaderTap keeps; the two `qa-credits` files are whole.

- `qa-credits.mkv`: ffmpeg 7 (libx264, 64x36, 1 fps, 150 s, a running clock). Chapters Intro 0:00, Episode 0:08, End Credits 1:40.
- `qa-credits.mp4`: the same, remuxed by ffmpeg with `-movflags +faststart` (moov first; Nero `chpl` and a QuickTime chapter track).
- `mkvmerge-ending.mkv`: mkvmerge v82: Prologue 0:00, Opening Credits 0:08, Episode 0:20, Ending 1:40-2:20, Preview 2:20.
- `mkvmerge-generic.mkv`: mkvmerge `--generate-chapters interval:30s`: Chapter 01 to Chapter 05 (Blu-ray style names, no credits name).
- `mkvmerge-none.mkv`: no chapters.
- `mkvmerge-postcredits.mkv`: Episode 0:00, End Credits 1:30, Post-Credits Scene 2:00 (story after the credits: no early card).

Rebuild (ffmpeg 7, mkvmerge v82). `ch.txt` is an FFMETADATA1 file with the three chapters above;
`chap.xml` and `post.xml` are Matroska chapter XML files with the chapters listed above.

    ffmpeg -f lavfi -i "color=c=0x203040:size=64x36:rate=1,drawtext=text='%{pts\:hms}':fontsize=12:fontcolor=white:x=2:y=10" \
      -i ch.txt -map 0:v -map_metadata 1 -map_chapters 1 -t 150 -c:v libx264 -preset veryslow -crf 40 -g 30 -pix_fmt yuv420p qa-credits.mkv
    ffmpeg -i qa-credits.mkv -map 0 -c copy -movflags +faststart qa-credits.mp4
    mkvmerge -o mkvmerge-ending.mkv --chapters chap.xml --no-chapters qa-credits.mkv
    mkvmerge -o mkvmerge-generic.mkv --generate-chapters interval:30s --generate-chapters-name-template "Chapter <NUM:2>" --no-chapters qa-credits.mkv
    mkvmerge -o mkvmerge-none.mkv --no-chapters qa-credits.mkv
    mkvmerge -o mkvmerge-postcredits.mkv --chapters post.xml --no-chapters qa-credits.mkv
