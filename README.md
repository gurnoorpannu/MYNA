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

## Apps it works with

MYNA isn't built for particular apps: it learns each task from your demo, so it works with any app you can open from the launcher. Nothing about any app is hard-coded.

**Tested end to end on:**

- **Zomato** (`com.application.zomato`): food ordering (search a restaurant, pick a dish, options, quantity, address)
- **Amazon Shopping** (`in.amazon.mShop.android.shopping`): shopping (search, pick a result, add to cart, checkout)

These two were chosen because they cover the hard cases: text drawn as images, taps that send no events, web pages, pop-ups and a payment step.

## Build and run

Requirements: Android Studio (JDK 25 bundled), an Android 11+ phone (minSdk 30). Tested on a Samsung Galaxy S25 Ultra, Android 16.

1. Clone and open the project in Android Studio.
2. Optional AI key: add `GEMINI_API_KEY=your_key` to `local.properties` (git-ignored). Without a key MYNA runs fully offline with rule-based fallbacks.
3. Run the `app` configuration on the phone (or `./gradlew :app:installDebug`).
4. On the phone: MYNA → **Turn it on** → Accessibility → MYNA → on. Allow the microphone the first time you tap the mic.

Run the tests: `./gradlew :app:testDebugUnitTest` (80+ JVM tests, including full replays against fake Zomato and Amazon screens).

## Using it

- **Teach tab** → type or speak the command, pick the app, tap **Show me once**, do the task, and let the 🔒 stop it at checkout (or tap **■ Done**).
- **Home tab** → tap the mic and speak, or tap **Run** on an automation card. **Make slight changes** edits the blanks (item, restaurant…) before running.
- **History tab** → every run, every step, and why it stopped.

## Documentation

- [Architecture (with diagrams)](docs/architecture.md)
- [Known limitations](docs/limitations.md)
- [Demo video script](docs/demo-script.md)
- [Presentation outline](docs/ppt-outline.md)

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
