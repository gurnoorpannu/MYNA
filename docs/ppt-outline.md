# Presentation outline — `Thapar_Legacy_Submission.pptx`

Use the **official PRISM template**. One idea per slide; the diagrams are in [architecture.md](architecture.md) (export the Mermaid diagrams as images from GitHub or mermaid.live).

1. **Title** — MYNA: Mimic Your iNteractions & Automate · Theme 3 Teachable Voice Automation · Team Legacy, Thapar
2. **Problem** — phones make people repeat the same 8–15 taps (order food, reorder essentials). Voice assistants can't do app tasks they weren't built for.
3. **Idea: show once, then just say it** — one demo → a recipe with blanks → replay by voice with new values; asks when unsure; never touches payment.
4. **Demo in 4 screenshots** — Teach · Learned step list · Home automations · 🔒 hand-off.
5. **Architecture** — big-picture diagram (teach time / run time / safety gate / AI only when lost).
6. **Speech → intent** — diagram 2; thresholds re-measured on Gemini embeddings (0.88 paraphrase, 0.68 "order pizza", ≤ 0.53 unrelated).
7. **Capturing the demo** — diagram 3; the real-app problems we solved: Compose lists with no text, Zomato's blank *Add item* box, Amazon web views, keyboard *Go* never reported → inferred from screen changes.
8. **Generalisation & blanks** — diagram 4 + example: `{item}` and `{restaurant}` from one sentence; habit defaults; paraphrases.
9. **Replay** — diagram 6: three-level finder + OCR, bounded fallbacks, never-taught goals (quantity, address), stuck reasons.
10. **Safety** — rules-only gate table; tuned on real screens (cart offers, Wallet tab, product colour radios are *not* payment); the one address exception; `GatedActor` is the only way to tap.
11. **Where AI is used** — the README table. A normal replay = 0 AI calls; a voice command ≈ 1; a teach = 1. Offline fallbacks for all.
12. **Results: the 14 tests** — pass/fail table (fill in after the full test run on 29 Sep).
13. **Numbers** — teach time vs manual time vs replay time (break-even after N runs, like SUGILITE); AI calls per run; unit tests (80+).
14. **Research backing** — table below.
15. **Target apps & known limitations** — Zomato, Amazon; top 5 from [limitations.md](limitations.md).
16. **What's next** — phone-call pause, re-teach from a step, more languages (Hindi OCR), on-device LLM.

## Research table (slide 14)

| Paper | What we took | What we added |
|---|---|---|
| SUGILITE (CHI 2017) | voice + demo teaching, unique element identity, neighbour values | payment hand-off, normal typing, inferred hidden taps |
| VASTA (Samsung AI, IUI 2020) | clean start, three-level finder, fuzzy text + OCR, similarity thresholds | tree + vision together; thresholds re-measured on Gemini |
| AutoDroid (MobiCom 2024) | screen as a short numbered list for the AI | used only as a last resort, one call |
| MobileGPT (MobiCom 2024) | sub-task recipes | universal search / result-pick goals |
| V-Droid (2025) | AI as a verifier over a shortlist | shortlist = visible tappables, gated |
| OS-Kairos (ACL 2025) | act vs ask by confidence | asks for missing blanks, "did you mean", finger-only buttons |
| Pop-up attack studies | never tap a pop-up's main action | close-type buttons / ✕ icons only |
| LearnAct, IFRAgent, AppAgentX (2025) | "why" lines, habit defaults, universal search | — |

## Numbers to measure on 29 Sep (slide 13)

| Task | Manual | Teach | Replay | AI calls |
|---|---|---|---|---|
| Zomato Margherita | _ s | _ s | _ s | _ |
| Amazon phone case | _ s | _ s | _ s | _ |

Break-even = teach time ÷ (manual − replay) runs.
