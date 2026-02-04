 📊 Benchmark Comparison: Phi-4-mini vs Qwen 2.5 1.5B

   Performance Summary

   | Metric           | Phi-4-mini          | Qwen 2.5 1.5B      | Winner               |
   | ---------------- | ------------------- | ------------------ | -------------------- |
   | Model Size       | 3.6 GB              | 1.5 GB             | 🏆 Qwen (58% smaller) |
   | Total Time       | 852.27s (~14.2 min) | 354.44s (~5.9 min) | 🏆 Qwen (2.4x faster) |
   | Pass 1 Time      | 533.38s             | 182.18s            | 🏆 Qwen               |
   | Pass 2 Time      | 318.89s             | 172.26s            | 🏆 Qwen               |
   | Characters Found | ✅ 5/5               | ✅ 5/5              | Tie                  |

   Quality Comparison

   Pass 1: Character Analysis & Voice Profiles

   | Aspect                        | Phi-4-mini                                  | Qwen 2.5 1.5B                                      |
   | ----------------------------- | ------------------------------------------- | -------------------------------------------------- |
   | Output Format                 | ✅ Clean JSON array with all profiles        | ⚠️ Separate JSON per character (not a single array) |
   |                               |
   | Character Identification      | ✅ Jax, Lyra, Kael, Zane, Mina               | ✅ Jax, Lyra, Kael, Zane, Mina                      |
   | Voice Profile Differentiation | ✅ Varied pitch/speed/energy per character   | ⚠️ All profiles use pitch=1.0,                      |
   | speed=1.0, energy=1.0         |
   | Tone Descriptions             | ✅ Detailed (e.g., "smirking and energetic") | ✅ Detailed (e.g., "confident and                   |
   | assertive")                   |
   | Age Assignments               | All "adult"                                 | ✅ Varied (teen, adult, middle-aged, elderly)       |

   Pass 2: Dialog Extraction

   | Aspect              | Phi-4-mini                         | Qwen 2.5 1.5B                                    |
   | ------------------- | ---------------------------------- | ------------------------------------------------ |
   | Dialog Extraction   | ✅ Clean Character: "Dialog" format | ⚠️ Mixed - some proper format, some narrative     |
   | Speaker Attribution | ✅ Accurate                         | ⚠️ Includes "SPEAKER ATTRIBUTION RULES" artifacts |
   | Narrative Handling  | ✅ Mostly excluded narrative        | ⚠️ Included narrative passages                    |

   Key Observations

   Phi-4-mini Strengths:
     • Cleaner, more structured output (single valid JSON array)
     • Better voice profile differentiation with varied pitch/speed/energy values
     • More accurate dialog-only extraction in Pass 2

   Qwen 2.5 1.5B Strengths:
     • 2.4x faster processing time (354s vs 852s)
     • 58% smaller model size (1.5 GB vs 3.6 GB)
     • More varied age assignments for characters
     • Included chapter structure in output

   Qwen 2.5 1.5B Weaknesses:
     • Voice profiles lack differentiation (all values at 1.0)
     • Pass 2 includes raw artifacts ("SPEAKER ATTRIBUTION RULES: QUOTED SPEECH")
     • Mixed narrative with dialog instead of clean separation

   Recommendation

   | Use Case                           | Recommended Model |
   | ---------------------------------- | ----------------- |
   | Speed-critical/Resource-limited    | 🏆 Qwen 2.5 1.5B   |
   | Quality-focused TTS voice profiles | 🏆 Phi-4-mini      |
   | Production dialog extraction       | 🏆 Phi-4-mini      |
   | Quick prototyping/testing          | 🏆 Qwen 2.5 1.5B   |

   Overall: Phi-4-mini produces higher quality, more usable output for TTS voice synthesis. Qwen 2.5 1.5B is
   significantly faster and smaller but requires more post-processing to clean up artifacts and differentiate
   voice profiles.
