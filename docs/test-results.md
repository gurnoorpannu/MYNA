# Test results

Device runs on **29–30 Sep 2026**, on a **Nothing Phone (3a)** (model A059, Android 16, SDK 36), plus the automated tests in this repository. App code: branch `testing/device-run-29sep`, merged into `main`.

## Automated tests

| Suite | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew :app:testDebugUnitTest` | **170 passed, 0 failed** |
| On-device tests | `./gradlew :app:connectedDebugAndroidTest` | **8 passed, 0 failed** (Nothing A059, 29 Sep) |

The unit tests include full replays against fake Zomato and Amazon screens. Every bug found on the phone below first got a failing test built from that phone's screen dump or run log, then the fix.

## T1–T14 on the phone

Each test was screen-recorded by the team (videos linked in the [README](../README.md#test-cases-t1t14)). "Run log" means the result was also checked in MYNA's own run log pulled from the phone.

| ID | Test | Evidence | Notes from the run logs |
|---|---|---|---|
| T1 | Teach — food | video; run log | 29 Sep 11:34: recorded 6 steps, stopped by the safety gate at "Place Order", 1 AI call, *"Learned: …"*. Recording took 19.6 s. |
| T2 | Exact replay | video; run log | 30 Sep 19:44: **hands-off** run, 41.2 s. MYNA pressed the options sheet's "Add item ₹114" itself and handed off at the cart. In other runs Zomato ignored MYNA's tap on "Add item" and MYNA asked for one finger tap (see limitations). |
| T3 | Paraphrase (2 sentences) | video (2 parts) | — |
| T4 | Slot: item (Farmhouse) | video | — |
| T5 | Slot: quantity (2) | video | — |
| T6 | Slot: address (Work) | video; run log | 30 Sep 20:06: *"delivery address set to Work"* on the "open Zomato" step, then hand-off at the cart (44.9 s). Needs a Work address the restaurant delivers to: when Zomato listed it under "DOES NOT DELIVER TO", MYNA now stops with that reason. |
| T7 | Screen change | video | Pop-up rule (close-type buttons only) is unit-tested; a real Zomato pop-up was closed on 30 Sep 18:34. |
| T8 | Teach — e-commerce (Amazon) | video; run log | Amazon flow taught on 30 Sep. |
| T9 | New search term on Amazon | video; run log | 30 Sep: "laptop stand" and "phone cover …" replays searched the new term, tapped Add to cart in the best-matching row and stopped at the cart (18.6–34.6 s per run). |
| T10 | Genuinely stuck | video | — |
| T11 | Credential boundary | same video as T2; run logs | Every successful Zomato run ended with zero taps at "Place Order" / "Add Payment Method", every Amazon run at "Pay ₹" or the cart. |
| T12 | Unknown intent | video | — |
| T13 | Ambiguity | video; chat log | 30 Sep 09:56: *"order a pizza"* → *"Did you mean: order margherita from Domino's on Zomato — like last time?"* |
| T14 | Reporting | video | — |

## Time: manual vs teach vs replay (slide 13)

| Task | Manual | Teach | Replay | Source |
|---|---|---|---|---|
| Zomato — Margherita from Domino's | 34 s (stopwatch, 29 Sep) | 19.6 s (29 Sep teach recording) | 41.2 s (30 Sep 19:44, hands-off) | run logs |
| Amazon — phone cover / laptop stand | not measured | not measured | 18.6–34.6 s (30 Sep, 14 successful runs) | run logs |

On this phone the Zomato replay is slower than doing it by hand (Zomato's pages and video banners take time to settle, and MYNA waits for each screen), so there is no time break-even: MYNA's value here is that the task runs hands-free from one sentence.

## Bugs found on the phone and fixed

28 fixes, each committed with the test that caught it:

| Area | Fix |
|---|---|
| Reporting (T14) | "stopped at step null" when a failed run had no steps |
| Privacy | WhatsApp chat previews were stored in recordings; teach-time AI prompt wasn't masked; every tap and typed text in every app was logged; backups copied recordings, run logs and chats |
| Quantity (T5) | "iphone 15 case" was read as quantity 15 |
| Run log | AI calls per run were never recorded |
| Search (T2) | Domino's page not recognised when its name sits in a list; result-less searches recovered by an AI guess instead of opening the result; results drawn without text weren't opened |
| Blanks (T1, T4, T9) | Search typed shorter or longer than said lost words or left leftovers ("laptop stand … phone 3a"); two typed letters ("ma") weren't linked to Margherita |
| Cart (T9) | Amazon runs pushed past the cart towards checkout and reported FAILED |
| Offline filling (T3) | Without AI, verbs like "put" and fixed words like "pizza" became values |
| Replay | One blank screen read ended a run; MYNA's own tap could hit its ■ Stop button; ADD on Zomato: lost demo tap, child-text button, sheet opening late |
| Safety (T11) | A cart whose button said "Add Payment Method" was not treated as the payment step |
| Recording | Taps on the page's own title were guessed although the user never made them |
| Address (T6) | False "address set" on "DOES NOT DELIVER TO"; switch now happens right after the app opens, waits for the home screen, and taps the real header |

## AI usage

Gemini's free tier ran out or was overloaded several times (HTTP 429 and 503). MYNA kept working with its offline fallbacks; those teaches got generic blank names and no paraphrases. `gemini-2.5-flash` is not available to new API keys, so the runs from 30 Sep evening used `gemini-3.5-flash` (set with `GEMINI_MODEL` in `local.properties`).
