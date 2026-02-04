Phi-mini LiteRT-LM benchmark
============================================================
Model: D:\Learning\Ai\Models\LLM\Phi\Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm
PDF: C:\Users\Pratik\source\Storyteller\Space story.pdf (6858 chars)
Wall time: Pass1=533.38s, Pass2=318.89s, Total=852.27s
============================================================

=== PASS 1: Characters + Traits + Voice profile ===

Characters: Jax, Lyra, Kael, Zane, Mina

1. Jax: The Cocky Captain
- Role: Pilot and self-appointed "Leader."
- Vibe: Overconfident, adventurous, and slightly rugged. He sounds like someone who has talked his way out of more trouble than he’s fought his way out of.
- Traits and Vibe: Medium-Low pitch, dynamic pacing, energetic tone, gravelly voice with a swagger.

2. Lyra: The Wide-Eyed Xenobiologist
- Role: Scientist and "Creature Whisperer."
- Vibe: Infectious curiosity and boundless empathy. She finds a giant spider "adorable" rather than terrifying.
- Traits and Vibe: Medium-High pitch, moderate to fast pacing, bright, breathless, warm tone, high inflection at the end of sentences.

3. Kael: The Stoic Cyborg
- Role: Security Officer and Tactical Specialist.
- Vibe: A blend of military precision and dry, deadpan humor. He is the grounded "straight man" to the rest of the chaotic crew.
- Traits and Vibe: Deep and resonant pitch, slow, rhythmic, deliberate pacing, monotone but rich tone, very little emotional fluctuation.

4. Zane: The Caffeinated Engineer
- Role: Lead Mechanic and Resident Panic-Attacker.
- Vibe: High-strung and brilliant. He’s always three seconds away from a breakdown or a breakthrough.
- Traits and Vibe: High pitch, extremely fast pacing, twitchy, sharp, alert tone, staccato rhythm.

5. Mina: The Ethereal Guide
- Role: Native Aurelian Scout.
- Vibe: Otherworldly, ancient, and deeply connected to the planet’s rhythm. She doesn't speak at people; she speaks through them.
- Traits and Vibe: Low and melodic pitch, very slow and flowing pacing, airy, soft, resonant tone, emphasis on vowels, calm, almost hypnotic cadence.

Voice profile (TTS):

```json
[
  {
    "character": "Jax",
    "voice_profile": {
      "pitch": 1.0,
      "speed": 1.2,
      "energy": 1.5,
      "gender": "male",
      "age": "adult",
      "tone": "smirking and energetic",
      "accent": "neutral",
      "emotion_bias": {
        "happy": 0.5,
        "sad": 0.1,
        "angry": 0.2,
        "neutral": 0.4,
        "fear": 0.1,
        "surprise": 0.4,
        "excited": 0.5,
        "disappointed": 0.1,
        "curious": 0.3,
        "defiant": 0.1
      }
    }
  },
  {
    "character": "Lyra",
    "voice_profile": {
      "pitch": 1.2,
      "speed": 1.0,
      "energy": 1.3,
      "gender": "female",
      "age": "adult",
      "tone": "bright, breathless with wonder, and warm",
      "accent": "neutral",
      "emotion_bias": {
        "happy": 0.5,
        "sad": 0.1,
        "angry": 0.2,
        "neutral": 0.4,
        "fear": 0.1,
        "surprise": 0.4,
        "excited": 0.5,
        "disappointed": 0.1,
        "curious": 0.3,
        "defiant": 0.1
      }
    }
  },
  {
    "character": "Kael",
    "voice_profile": {
      "pitch": 0.8,
      "speed": 0.7,
      "energy": 0.5,
      "gender": "male",
      "age": "adult",
      "tone": "monotone but rich",
      "accent": "neutral",
      "emotion_bias": {
        "happy": 0.3,
        "sad": 0.1,
        "angry": 0.2,
        "neutral": 0.4,
        "fear": 0.1,
        "surprise": 0.4,
        "excited": 0.5,
        "disappointed": 0.1,
        "curious": 0.3,
        "defiant": 0.1
      }
    }
  },
  {
    "character": "Zane",
    "voice_profile": {
      "pitch": 1.5,
      "speed": 1.5,
      "energy": 1.5,
      "gender": "male",
      "age": "adult",
      "tone": "twitchy, sharp, and alert",
      "accent": "neutral",
      "emotion_bias": {
        "happy": 0.5,
        "sad": 0.1,
        "angry": 0.2,
        "neutral": 0.4,
        "fear": 0.1,
        "surprise": 0.4,
        "excited": 0.5,
        "disappointed": 0.1,
        "curious": 0.3,
        "defiant": 0.1
      }
    }
  },
  {
    "character": "Mina",
    "voice_profile": {
      "pitch": 0.5,
      "speed": 0.5,
      "energy": 0.5,
      "gender": "female",
      "age": "adult",
      "tone": "airy, soft, and resonant",
      "accent": "neutral",
      "emotion_bias": {
        "happy": 0.5,
        "sad": 0.1,
        "angry": 0.2,
        "neutral": 0.4,
        "fear": 0.1,
        "surprise": 0.4,
        "excited": 0.5,
        "disappointed": 0.1,
        "curious": 0.3,
        "defiant": 0.1
      }
    }
  }
]
```

=== PASS 2: Character:Dialog mapping ===

Jax: "See? I told you the landing gear was optional."
Zane: "We’re missing a wing, Jax."
Mina: "You seek the Pulse, but the path is guarded by the Crystalline Hydra. If you want to live, follow the silence, not the light."
Kael: "Standard protocol?"
Jax: "Standard protocol. Zane, cause a distraction. Lyra, find its weak spot. Kael, try not to get shattered."
Lyra: "It’s not attacking! It’s harmonizing! We’re out of tune!"
Jax: "It paused, its crystalline scales dimming from an angry red to a calm azure. It lowered its heads, allowing them to pass over the bridge of its own back."
Mina: "It requires a trade. The Griffin doesn't want gold. It wants a memory."
---
Character: "See? I told you the landing gear was optional."
Speaker Attribution: Jax: "See? I told you the landing gear was optional."
Character: "We’re missing a wing, Jax."
Speaker Attribution: Zane: "We’re missing a wing, Jax."
Character: "You seek the Pulse, but the path is guarded by the Crystalline Hydra. If you want to live, follow the silence, not the light."
Speaker Attribution: Mina: "You seek the Pulse, but the path is guarded by the Crystalline Hydra. If you want to live, follow the silence, not the light."
Character: "Standard protocol?"
Speaker Attribution: Kael: "Standard protocol?"
Character: "Standard protocol. Zane, cause a distraction. Lyra, find its weak spot. Kael, try not to get shattered."
Speaker Attribution: Jax: "Standard protocol. Zane, cause a distraction. Lyra, find its weak spot. Kael, try not to get shattered."
Character: "It’s not attacking! It’s harmonizing! We’re out of tune!"
Speaker Attribution: Lyra: "It’s not attacking! It’s harmonizing! We’re out of tune!"
Character: "It paused, its crystalline scales dimming from an angry red to a calm azure. It lowered its heads, allowing them to pass over the bridge of its own back."
Speaker Attribution: Jax: "It paused, its crystalline scales dimming from an angry red to a calm azure. It lowered its heads, allowing them to pass over the bridge of its own back."
Character: "It requires a trade. The Griffin doesn't want gold. It wants a memory."
Speaker Attribution: Mina: "It requires a trade. The Griffin doesn't want gold. It wants a memory."

Jax: "Let's get out of here," Jax said, his voice unusually soft. "I think the Rambler has had enough adventure for one day."