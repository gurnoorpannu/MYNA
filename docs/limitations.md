# Known limitations

Honest list, from testing on a Galaxy S25 Ultra (Android 16) with Zomato and Amazon, 23–27 Sep 2026.

## Apps that resist automation

- **Zomato's "Add item ₹…" button ignores accessibility input.** Every kind of tap an accessibility service can send (gestures of any length, click actions on the button and its parents) is rejected; only a real finger works. MYNA reselects the demo's options, tries twice, then asks: *"Please tap the add button on the sheet."* It waits up to 30 s and carries on.
- **Taps inside web pages and Compose lists send no events** (Amazon product pages, Zomato search suggestions). MYNA infers these taps from what changed on screen. When a tap can't be inferred, replay recovers (for example it opens the best-matching search result itself), but the step list may show fewer steps than you did.
- **Zomato customisations:** options you change on the options sheet are remembered, but options further down a long sheet are only captured if you scroll past them while teaching.

## Understanding

- **On-device OCR reads Latin script only.** Text drawn as images in Hindi can't be read (MYNA stops on Hindi screens anyway, with a reason).
- **Quantity and address** are recognised from common phrasings ("2 margheritas", "two", "deliver to work", "at home"). Unusual phrasings fall back to the AI blank filler.
- **Speech corrections** only use words MYNA has already seen in your recipes. A brand-new dish name mis-heard by the recogniser is not corrected.

## AI

- **Gemini free tier: 20 generation calls per day per project.** Each teach uses 1, each voice command about 1. When the quota runs out MYNA keeps working with rule-based fallbacks, but blank names are generic (`item`, `query`) and there are no paraphrases or "why" lines.
- **The API key is built into the APK** (from `local.properties`). Use a restricted key and rotate it after judging.

## Scope

- **Replay stops at the payment step by design.** It never places orders or pays.
- **Voice runs only Home automations.** New tasks are added to Home automatically; remove old ones you don't want voice to pick.
- **"Re-teach from a step" is not built.** You can delete individual steps from a recipe, or teach the whole task again (re-teaching replaces the old version on Home).
- **Pausing for phone calls is not built.** A call during replay will usually end the run as stuck, with the reason in History.
- **Leftover cart items** can change what the app shows (for example Zomato's "repeat last customisation?" sheet). MYNA handles the common cases, but an empty cart gives the most reliable demo.
- Tested on one device model and Android 16. Other screen sizes should work (MYNA uses the screen tree, not fixed coordinates) but were not tested.
