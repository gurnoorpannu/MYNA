# MYNA — Mimic Your iNteractions & Automate

**Show once, then just say it.** MYNA is an Android app that learns a task from one voice + tap demo, turns it into a reusable recipe with blanks, and replays it when you ask by voice — even when the wording, the item, the quantity or the address changes. It asks you when it is unsure, and it never taps on payment, OTP, password or login screens.

Samsung PRISM Gen AI Hackathon 3.0 · Theme 3: Teachable Voice Automation · Team Legacy

---

## What it does

| You | MYNA |
|---|---|
| Say *"Order a Margherita pizza from Domino's on Zomato"*, tap **Show me once**, and do it yourself | Records every step, stops by itself at the payment screen, and says *"Learned: order Margherita from Domino's on Zomato"* |
| Say *"get me a margherita from dominos"* | Recognises the same task and replays it |
| Say *"order a farmhouse from dominos"* | Same recipe, new item — adds Farmhouse |
| Say *"order 2 margheritas and deliver to work"* | Sets the quantity with the + button and picks the Work address — steps it was never shown |
| Say *"order pizza"* | *"Did you mean: order Margherita from Domino's — like last time?"* |
| Say *"book a cab"* | *"I haven't learned that yet. Want to teach me?"* |
| Anything that reaches a payment / OTP / login screen | Zero taps. *"Your turn."* |
| *"Did the last run work?"* | *"No. It stopped at step 3 because Domino's isn't taking orders right now."* |

All automation goes through Android's **AccessibilityService** — no app SDKs, no deep links, no web fallbacks.

## Demo video

**[▶ Watch the demo (≤ 5 min, one take)](https://drive.google.com/file/d/1DHrpgInpbme7hSHadp0A3DoQJjBCN2yX/view?usp=sharing)** — (a) teach one flow by voice + taps, (b) replay with the exact command, (c) with a paraphrase, (d) with a changed value, (e) MYNA asking a question.

## Submission files

| What | Where |
|---|---|
| Presentation | [Thapar_Legacy_Submission.pdf](Thapar_Legacy_Submission.pdf) |
| Installable app | [apk/MYNA.apk](apk/MYNA.apk) (Android 11+; turn on MYNA under Settings → Accessibility after installing) |
| Demo video (≤ 5 min) | [Google Drive](https://drive.google.com/file/d/1DHrpgInpbme7hSHadp0A3DoQJjBCN2yX/view?usp=sharing) |
| Requirements | [requirements.txt](requirements.txt) |
| Source code | this repository (`app/`) |

## Test cases (T1–T14)

One screen recording per official test case (the official checks are from the PRISM Theme 3 evaluation criteria).

| ID | Test | What the judge does | What MYNA must do | Video |
|---|---|---|---|---|
| T1 | Teach — food | Says *"Order a Margherita pizza from Domino's on Zomato"*, taps it once up to payment | Records command + steps, confirms *"Learned: …"*, saves it; steps inspectable | [▶ video](https://drive.google.com/file/d/1_8SRqc3uTKGSk0BSgZCSF4vEIcyE3kn_/view?usp=sharing) |
| T2 | Exact replay | Repeats the T1 sentence | Reaches the payment page unattended, correct item and restaurant | [▶ video](https://drive.google.com/file/d/1UdboGWjos1nbge9cFEP0txYmyd5g2eEP/view?usp=sharing) |
| T3 | Paraphrase | *"Get me a margherita from dominos"* and *"I want to order margherita pizza on zomato"* | Both map to the T1 flow and replay | [▶ video](https://drive.google.com/file/d/1W9ag3FTYxI1ik6kyNtZTnRRAAddKD5rV/view?usp=sharing) (part 1) · [▶ video](https://drive.google.com/file/d/1xgzprNUGJWVCOY7L72v4Qs-XmVjzEyR7/view?usp=sharing) (part 2) |
| T4 | Slot: item | *"Order a Farmhouse pizza from Domino's on Zomato"* | Same flow, Farmhouse (not Margherita) in the cart | [▶ video](https://drive.google.com/file/d/1jXLtnECz63RDJtzaHyICwshPD7Yk-rwI/view?usp=sharing) |
| T5 | Slot: quantity | *"Order two Margherita pizzas from Domino's"* | Quantity 2 in the cart | [▶ video](https://drive.google.com/file/d/1N0sA94C6mmVCAMF14OpITHyva7Ftkdqy/view?usp=sharing) |
| T6 | Slot: address | *"Order a Margherita from Domino's, deliver to work"* | Work address selected at checkout | [▶ video](https://drive.google.com/file/d/1yQXo8WZwuWxmu86n0TKqWi6fqLg_tkix/view?usp=sharing) |
| T7 | Screen change | Pop-up on open, or an item already in the cart, then T2 again | Handles it (closes the pop-up / proceeds) or asks a specific question | [▶ video](https://drive.google.com/file/d/1jUOKafKPZFk68TSBlwYNXOW_a2ckymRB/view?usp=sharing) |
| T8 | Teach — e-commerce | Teaches a second flow on Amazon | Second flow learned in a second app, distinct from T1 | [▶ video](https://drive.google.com/file/d/1XQVThx0wdKkZlP-vzd9fKe7BiztglyNp/view?usp=sharing) |
| T9 | Cross-app slot + replay | Same Amazon flow with a new search term | Correct item in the cart | [▶ video](https://drive.google.com/file/d/1vFv4YAFWMZxG2dz8Bv7HT8ZNDkExr85R/view?usp=sharing) |
| T10 | Genuinely stuck | Hindi language or logged out, then T2 | Asks or reports a specific failure within 30 s; no wrong taps | [▶ video](https://drive.google.com/file/d/12KClfFuObwG0loLhhlsYmG0tKPt2tHbk/view?usp=sharing) |
| T11 | Credential boundary | Lets T2 reach payment | Zero taps on payment/OTP; explicit *"Your turn"* | [▶ video](https://drive.google.com/file/d/1UdboGWjos1nbge9cFEP0txYmyd5g2eEP/view?usp=sharing) (same run as T2) |
| T12 | Unknown intent | *"Book a cab to the airport"* | Says it hasn't learned this and offers to be taught | [▶ video](https://drive.google.com/file/d/13s0Fas3EZZkmiAMXGPjukk7matv81Miv/view?usp=sharing) |
| T13 | Ambiguity | *"Order pizza"* | Asks or confirms (*"Did you mean … like last time?"*), never a silent wrong guess | [▶ video](https://drive.google.com/file/d/1tV3k1J83YhE_SfjHM15MsHQAy6yXoRWg/view?usp=sharing) |
| T14 | Reporting | *"Did the last run succeed?"* after T2 and T10 | Clear success/failure with the step where it stopped | [▶ video](https://drive.google.com/file/d/15ZNtt4ZM1P5HRv7ys_-oelMpMa4bSPHk/view?usp=sharing) |

## Documentation

- [Architecture (with diagrams)](docs/architecture.md)
- [Known limitations](docs/limitations.md)

## Apps it works with

MYNA isn't built for particular apps: it learns each task from your demo, so it works with any app you can open from the launcher. Nothing about any app is hard-coded.

**Tested end to end on** (Samsung Galaxy S25 Ultra and Nothing Phone (3a), both Android 16):

- **Zomato** (`com.application.zomato`): food ordering (search a restaurant, pick a dish, options, quantity, address)
- **Amazon Shopping** (`in.amazon.mShop.android.shopping`): shopping (search, pick a result, add to cart, checkout)

These two were chosen because they cover the hard cases: text drawn as images, taps that send no events, web pages, pop-ups and a payment step.

## Build and run

Requirements: Android Studio (JDK 25 bundled), an Android 11+ phone (minSdk 30). Tested on a Samsung Galaxy S25 Ultra and a Nothing Phone (3a), both Android 16.

1. Clone and open the project in Android Studio.
2. Optional AI key: add `GEMINI_API_KEY=your_key` to `local.properties` (git-ignored). Without a key MYNA runs fully offline with rule-based fallbacks. The model is `GEMINI_MODEL` (default `gemini-2.5-flash`; newer API keys can't use it, so set e.g. `GEMINI_MODEL=gemini-3.5-flash`).
3. Run the `app` configuration on the phone (or `./gradlew :app:installDebug`). An installable APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. The API key is built into the APK, so share it only with the judges.
4. On the phone: MYNA → **Turn it on** → Accessibility → MYNA → on. Allow the microphone the first time you tap the mic.

Run the tests:

- `./gradlew :app:testDebugUnitTest` — 172 JVM tests, including full replays against fake Zomato and Amazon screens built from real screen dumps.
- `./gradlew :app:connectedDebugAndroidTest` — 8 on-device tests (app launch and tabs, recipe storage, History, the safety gate on real accessibility nodes).

## Using it

- **Teach tab** → type or speak the command, pick the app, tap **Show me once**, do the task, and let the 🔒 stop it at checkout (or tap **■ Done**).
- **Home tab** → tap the mic and speak, or tap **Run** on an automation card. **Make slight changes** edits the blanks (item, restaurant…) before running.
- **History tab** → every run, every step, and why it stopped.

## Project layout

```
app/src/main/java/.../
  a11y/       MynaService (the AccessibilityService), tree capture, tree dumps
  record/     Recorder: demo → steps (taps, typing, search, sheets, inferred taps)
  compile/    Compiler: steps → recipe with blanks (+1 AI call)
  intent/     IntentMatcher, speech fixes, quantity/address parsing
  replay/     Executor, Finder, run logs, privacy mask
  safety/     SafetyGate (hard rules) + GatedActor (the only way to tap)
  screen/     UiNode snapshot + element identity
  llm/        Gemini client (JSON schema, retry, mock mode)
  ui/         Compose UI (Home, Teach, History, chat)
```
