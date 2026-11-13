# SDK entry format

One JSON file per SDK, named `<id>.json`. The scanner loads every `*.json` in this folder.

```json
{
  "id": "firebase-analytics",
  "name": "Google Analytics for Firebase",
  "vendor": "Google",
  "category": "analytics",
  "match": [
    "com.google.firebase:firebase-analytics",
    "com.google.firebase:firebase-analytics-ktx"
  ],
  "role": "service-provider",
  "dataTypes": [
    {
      "type": "Device or other IDs / Device or other IDs",
      "collected": true,
      "shared": false,
      "ephemeral": false,
      "purposes": ["Analytics"],
      "when": "Always. Also the Android Advertising ID when the app has the AD_ID permission.",
      "sourceUrl": "https://firebase.google.com/docs/android/play-data-disclosure"
    }
  ],
  "ask": [
    "Do you link analytics events to a signed-in user ID? If yes, also declare Personal info / User IDs."
  ],
  "sources": [
    {
      "url": "https://firebase.google.com/docs/android/play-data-disclosure",
      "title": "Prepare for Google Play's Data safety section (Firebase)",
      "checked": "2026-11-13"
    }
  ],
  "confidence": "high",
  "notes": "Free text: anything a reviewer should know."
}
```

## Fields

| field | rules |
|---|---|
| `id` | kebab-case, equals the file name |
| `match` | Maven `group:artifact` coordinates **without version**. A dependency matches when its `group:artifact` equals an entry, or starts with an entry that ends in `*` (e.g. `"com.google.firebase:firebase-perf*"`). Include `-ktx` and BOM-era variants. |
| `role` | `service-provider` (processes data on the developer's behalf; Play does **not** count this as sharing), `third-party` (uses data for its own purposes, e.g. ad and attribution networks; counts as sharing), or `mixed` (explain in `notes`) |
| `dataTypes[].type` | **Exactly** one of the canonical labels below |
| `dataTypes[].purposes` | One or more of: `App functionality`, `Analytics`, `Developer communications`, `Fraud prevention, security, and compliance`, `Advertising or marketing`, `Personalization`, `Account management` |
| `dataTypes[].shared` | `true` only when the vendor's own guidance says the data is shared, or the vendor uses it for its own purposes |
| `dataTypes[].when` | Conditions: always, only if a feature is used, only with a permission, etc. |
| `dataTypes[].conditional` | `true` when the type is collected **only in some setups** (e.g. only if the app calls `setUserId`). The scanner then asks about it instead of putting it in the draft. Omit or `false` for data the SDK collects by default. |
| `dataTypes[].sourceUrl` | The page that states this specific data type |
| `ask` | Questions the agent must put to the developer because static analysis cannot know the answer |
| `sources[].checked` | `YYYY-MM-DD` the page was read |
| `confidence` | `high` = vendor publishes a Play Data safety guide · `medium` = vendor privacy docs list the data but not in Play terms · `low` = inferred, needs review |

## Canonical data type labels

```
App activity / App interactions
App activity / In-app search history
App activity / Installed apps
App activity / Other actions
App activity / Other user-generated content
App info and performance / Crash logs
App info and performance / Diagnostics
App info and performance / Other app performance data
Audio files / Music files
Audio files / Other audio files
Audio files / Voice or sound recordings
Calendar / Calendar events
Contacts / Contacts
Device or other IDs / Device or other IDs
Files and docs / Files and docs
Financial info / Credit score
Financial info / Other financial info
Financial info / Purchase history
Financial info / User payment info
Health and fitness / Fitness info
Health and fitness / Health info
Location / Approximate location
Location / Precise location
Messages / Emails
Messages / Other in-app messages
Messages / SMS or MMS
Personal info / Address
Personal info / Email address
Personal info / Name
Personal info / Other info
Personal info / Phone number
Personal info / Political or religious beliefs
Personal info / Race and ethnicity
Personal info / Sexual orientation
Personal info / User IDs
Photos and videos / Photos
Photos and videos / Videos
Web browsing / Web browsing history
```
