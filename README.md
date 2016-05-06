# process-streaming
Example of video-clipping using piping through the ffmpeg process.

Right now this doesn't work. It produces a truncated video clip much smaller than it's supposed to be.

To try it for yourself you'll need ffmpeg on your path. On OSX you can `brew install ffmpeg` for this.

Then just `sbt run`. You'll have two files afterwards. A source video sample in `$PWD/sample.mp4` and a clip at `$PWD/clip.mp4`.
