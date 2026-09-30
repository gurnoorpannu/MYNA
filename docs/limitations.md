# Known limitations

Honest list, from testing on a Galaxy S25 Ultra (Android 16) with Zomato and Amazon, 23–27 Sep 2026, and on a Nothing Phone (3a) (Android 16), 29–30 Sep 2026.

## Apps that resist automation

- **Zomato's "Add item ₹…" button often ignores accessibility input.** On the S25 Ultra every kind of accessibility tap was rejected; on the Nothing Phone (3a) it worked in some runs (MYNA pressed it by reading the screen) and not in others. When it fails, MYNA reselects the demo's options, tries twice, then asks: *"Please tap the add button on the sheet."* It waits up to 30 s and carries on. A run that needs that finger tap is not fully unattended.
- **Zomato doesn't always report the ADD tap or the result you opened while teaching.** Replay makes up for it (it taps ADD on the dish you typed, and opens the result matching your search), but the step list may show fewer steps than you did.
- **Taps inside web pages and Compose lists send no events** (Amazon product pages, Zomato search suggestions). MYNA infers these taps from what changed on screen. When a tap can't be inferred, replay recovers (for example it opens the best-matching search result itself), but the step list may show fewer steps than you did.
- **Zomato customisations:** options you change on the options sheet are remembered, but options further down a long sheet are only captured if you scroll past them while teaching.

## Understanding

- **On-device OCR reads Latin script only.** Text drawn as images in Hindi can't be read (MYNA stops on Hindi screens anyway, with a reason).
- **Quantity and address** are recognised from common phrasings ("2 margheritas", "two", "deliver to work", "at home"). Unusual phrasings fall back to the AI blank filler. A number right after a product word is a model, not a count ("iphone 15 case").
- **The Run button replays the taught values.** It can't carry "deliver to work" or "two": say or type the command in the chat for those.
- **"Deliver to work" needs a Work address the restaurant delivers to.** MYNA switches the address from the app's home header right after opening it. If the app lists the address under "does not deliver to", MYNA stops and says so. Don't change the address while teaching: that makes the address a fixed step.
- **Speech corrections** only use words MYNA has already seen in your recipes, and only close matches: a brand-new dish name, or a big mis-hearing ("marketer" for "margherita", "margretta" on the first teach), is not corrected. Voice commands fail in background noise; typing in the chat box always works.

## AI

- **Gemini free tier: about 20 generation calls per day per project, and models are sometimes overloaded** (HTTP 429 / 503 on 30 Sep). Each teach uses 1, each voice command about 1. When a call fails MYNA keeps working with rule-based fallbacks, but blank names are generic (`item`, `query`) and there are no paraphrases or "why" lines. Re-teach once the AI responds for the best recipe.
- **`gemini-2.5-flash` isn't available to new API keys.** Set `GEMINI_MODEL=gemini-3.5-flash` (or another available flash model) in `local.properties`.
- **The API key is built into the APK** (from `local.properties`). Use a restricted key and rotate it after judging.

## Scope

- **Replay stops at the payment step by design.** It never places orders or pays.
- **Voice runs only Home automations.** New tasks are added to Home automatically; remove old ones you don't want voice to pick.
- **"Re-teach from a step" is not built.** You can delete individual steps from a recipe, or teach the whole task again (re-teaching replaces the old version on Home).
- **Pausing for phone calls is not built.** A call during replay will usually end the run as stuck, with the reason in History.
- **Leftover cart items** can change what the app shows (for example Zomato's "repeat last customisation?" sheet). MYNA handles the common cases, but an empty cart gives the most reliable demo. Zomato keeps one cart per restaurant; MYNA does not clear other restaurants' carts.
- **Replay can be slower than doing it by hand** on some phones (Zomato's pages and video banners take time to settle; 41 s replay vs 34 s by hand on the Nothing Phone (3a)).
- Tested on two device models (Galaxy S25 Ultra, Nothing Phone (3a)), both Android 16. Other screen sizes should work (MYNA uses the screen tree, not fixed coordinates).
- **The Teach tab offers five apps** (Zomato, Amazon, WhatsApp, Myntra, Swiggy). Other launcher apps work the same way but need adding to that list.
