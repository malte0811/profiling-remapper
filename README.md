A utility to remap profiling results from modded Minecraft into human-readable names. Currently, HPROF (heap dump) and
NPS (CPU profiling) files are supported. This is mostly a hacked-together project that works "well enough" to make sense
of the data without a remapping bot, the remapping is not perfect. Especially NPS remapping is not particularly well
tested.

The HPROF remapper (and this entire repository) is originally based on the
[hprof-parser](https://github.com/eaftan/hprof-parser/) by Eddie Aftandilian.
