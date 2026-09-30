# MYNA architecture

MYNA has two phases. **Teach time** turns one demo into a recipe. **Run time** turns a spoken command into a replay of that recipe. A safety gate sits in front of every tap. AI is used for learning, understanding commands and last-resort recovery — never on the normal replay path.

## 1. The big picture

```mermaid
flowchart LR
    subgraph Teach["Teach time"]
        V1[Voice / typed command] --> R[Recorder<br/>AccessibilityService events]
        R --> C[Compiler<br/>blanks + 1 AI call]
        C --> S[(Recipe store<br/>JSON on device)]
    end
    subgraph Run["Run time"]
        V2[Voice] --> I[Intent matcher<br/>embeddings + 1 AI call]
        I --> E[Executor<br/>no AI]
        E --> G{Safety gate<br/>every tap}
        G -->|safe| A[Tap / type in the app]
        G -->|payment · OTP · login| H[Hand-off: 'Your turn']
        E --> L[(Run log)]
    end
    S --> I
    S --> E
    L --> Rep[History / 'did it work?']
    E -. only when lost .-> AI[AI helper<br/>numbered screen list]
```

## 2. Speech → intent

```mermaid
flowchart TD
    Mic[Tap mic on Home] --> SR[In-app SpeechRecognizer<br/>live words in the circle<br/>biased toward known words]
    SR --> Fix[Speech fix<br/>'marherator' → 'Margherita'<br/>Jaro-Winkler vs recipe vocabulary]
    Fix --> Meta{Report question?<br/>'did it work'}
    Meta -->|yes| Rep[Read newest run log → one sentence]
    Meta -->|no| Ex[Extras: quantity '2', address 'Work']
    Ex --> Rank[Rank Home automations<br/>cosine similarity, Gemini embeddings]
    Rank -->|< 0.60| T12["'I haven't learned that — teach me?'"]
    Rank -->|0.60 – 0.70| T13["'Did you mean … like last time?'"]
    Rank -->|≥ 0.70| Fill[Fill blanks<br/>1 AI call · template fallback]
    Fill -->|some blanks unsaid| Ask["'Which restaurant? Last time: Domino's'"]
    Fill -->|2+ blanks changed| Conf["'Just to check: …?'"]
    Fill --> Run[Executor]
```

Thresholds are VASTA's, re-measured on `gemini-embedding-001`: paraphrases score 0.75–0.88, *"Order pizza"* 0.68, unrelated commands ≤ 0.53.

## 3. UI-tree capture (recording a demo)

```mermaid
flowchart TD
    Ev[Accessibility events<br/>click · text · window · focus] --> Snap[Snapshot every window of the app<br/>into a UiNode tree]
    Snap --> Id[Element identity<br/>label · sub-texts · id · class · index<br/>card anchor · neighbours · unique key]
    Id --> Rec[Recorder steps<br/>launch · tap · type · key · goals]
    Tick[Every 1.5 s + on settle] --> Inf{A screen changed<br/>with no event?}
    Inf -->|typed, then landed| SG[search goal:<br/>'search dominos, open Domino's Pizza']
    Inf -->|sheet closed| CS[confirm_sheet + its selected options]
    Inf -->|keyboard left| Sub[typing + Enter]
    Inf -->|web page changed| Diff[tap inferred from what's new<br/>'Add to cart' → cart count up]
    SG --> Rec
    CS --> Rec
    Sub --> Rec
    Diff --> Rec
    Rec --> Gate{Safety gate on the screen}
    Gate -->|payment / Place Order| Stop[Recording stops by itself]
```

Why so much inference: real apps hide taps. Zomato's search suggestions are text-less Compose rows, its **Add item** button is a blank drawn box, and Amazon's product pages are web views — none of them send a click event.

## 4. Generalisation (compiling a recipe)

```mermaid
flowchart LR
    R[Raw recording] --> B[Blank detection, no AI<br/>spoken words ↔ typed / searched / tapped text]
    B --> AI[1 Gemini call<br/>blank names · summary · paraphrases<br/>why lines · screen notes · sub-tasks · noise]
    AI --> P[Result picks<br/>tap after a search → 'open the best match']
    P --> D[Habit defaults<br/>sheet options: New Hand Tossed, Regular]
    D --> Recipe[(Recipe on Home)]
```

Example: *"Order a Margherita pizza from Domino's on Zomato"* → `{item}` = Margherita (typed in the menu search + the card the Add button sits on), `{restaurant}` = Domino's (the search + the result opened). Tapped-but-unsaid choices stay fixed as habit defaults.

## 5. Blank (slot) extraction at run time

```mermaid
flowchart LR
    U["'order 2 farmhouse from dominos and deliver to work'"] --> X[Extras<br/>qty = 2 · address = Work]
    X --> F["AI fill → item = Farmhouse, restaurant = Domino's<br/>(said = true for both)"]
    F --> M[Merge with last values<br/>unsaid → ask or keep]
    M --> V["{item: Farmhouse, restaurant: Domino's, qty: 2, address: Work}"]
```

## 6. Replay

```mermaid
flowchart TD
    St[Next step] --> Scr[Wait for screen to settle<br/>capture tree]
    Scr --> Gate{Safety gate}
    Gate -->|blocked| Hand[Hand-off, zero taps]
    Gate --> Stk{Stuck checks<br/>Hindi screen · closed shop<br/>same screen 3× · 30 s}
    Stk -->|yes| Reason[Stop with a specific reason]
    Stk --> Find[Finder<br/>1 key · 2 same element · 3 fuzzy ≥ 0.8<br/>then OCR]
    Find -->|found| Act[Tap / type via GatedActor]
    Find -->|not found| Fb[Fallbacks, bounded<br/>close pop-up ✕ · open search · press Enter<br/>scroll the page · open best result]
    Fb --> Find
    Fb -->|all used| Help[AI helper: pick one numbered element or stop]
    Act --> Ver[Verify next screen]
    Ver --> Extra[Never-taught goals<br/>set quantity · pick address · confirm sheet]
    Extra --> St
```

## 7. Safety gate (T11)

Rules only, no AI. Checked on every settled screen and before every tap or typed character.

| Blocks | How it's detected |
|---|---|
| Credentials | password fields; fields asking for OTP, CVV, card number, UPI PIN |
| Payment | known payment apps; 3+ payment-method kinds on screen (UPI, card, net banking, wallet, COD, EMI), or 2 with payment radio buttons |
| Final order | any tappable *Place Order / Pay ₹ / Proceed to pay*, or a button that leads into payment (*Add / Select payment method*) |
| Login | a text field plus a *Log in / Send OTP / Verify* button |

Tuned on real screens: a cart advertising card offers, Amazon's "Wallet" tab and product pages with colour radios are **not** payment screens. The single exception: on a *Place Order* screen (never a payment or credential screen) MYNA may use the **address picker** for "deliver to Work"; the Place Order button itself stays blocked. In practice the address is switched earlier, right after the app opens, from its home header ("Home ▾ …" → saved addresses → Work); an address the app says it can't deliver to stops the run with that reason. `GatedActor` is the only code allowed to tap or type in another app.

## 8. Where things live

| Module | Files |
|---|---|
| Accessibility service, tree capture | `a11y/MynaService.kt`, `a11y/UiTree.kt` |
| Recorder | `record/Recorder.kt`, `screen/Identity.kt` |
| Compiler | `compile/Compiler.kt` |
| Intent matcher | `intent/IntentMatcher.kt`, `intent/SpeechFix.kt`, `intent/Extras.kt` |
| Replay | `replay/Executor.kt`, `replay/Finder.kt`, `replay/RunLog.kt`, `replay/Privacy.kt` |
| Safety | `safety/SafetyGate.kt`, `safety/GatedActor.kt` |
| AI client | `llm/Llm.kt`, `llm/JsonSchema.kt` |
