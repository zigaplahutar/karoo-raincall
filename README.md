# RainCall

Text-based storm warnings for the Hammerhead Karoo 3: when rain will hit you, how hard,
and which way to go to avoid it.

Everything runs on the device. There is no server, no API key and no account —
the same shape as the ARSO Radar extension, just with more arithmetic on board.

**Weather data by RainViewer** — <https://www.rainviewer.com/>
Attribution is mandatory under RainViewer's free terms of use and must stay visible
in the app. It is not decoration to drop when space is tight.

---

## Status

Step 1 of 9 is done: radar plumbing and the maths underneath it.

| # | Step | State |
| --- | --- | --- |
| 1 | Tile maths, dBZ decoding, RainViewer API model | **done** |
| 2 | Rider speed and heading from the Karoo SDK | **done** |
| 3 | Cell motion vector (own estimate; nowcast comparison pending) | **partly done** |
| 4 | Distance, ETA, uncertainty cone | **done** |
| 5 | Hail threshold wired into the message | **done** |
| 6 | Field rendering, sized from the real field dimensions | **done** |
| 7 | `InRideAlert` triggers and hysteresis | **done** |
| 8 | Evasion: four scenarios compared | **done** |
| 9 | Pipeline, home advice, wet roads, post-ride summary | **done** |

---

## Decisions worth remembering

### Colour scheme 0, not the pretty one

RainViewer's scheme 0 ("Black and White") is not a map palette — it is a linear
encoding of the measurement:

```
grey 1..127    ->  rain, dBZ = grey - 32
grey 129..255  ->  snow, dBZ = grey - 160
transparent    ->  no data
```

Checked against every sampled row of RainViewer's published colour table. Two
consequences: decoding is exact rather than a nearest-colour guess, and telling snow
from rain comes free rather than needing separate work later.

This only holds if the tile URL asks for **scheme 0 with smoothing off**. Smoothed
tiles interpolate between values, so a pixel becomes a blend rather than a
measurement. `RainViewerApiTest` pins all three parameters for exactly this reason.

A fallback decoder for scheme 2 (Universal Blue) exists in case scheme 0 turns out to
be unavailable. It is lossy above dBZ 64 and cannot see snow, so it is a fallback and
nothing more.

### Zoom 8

At Slovenian latitudes that is 212 m per pixel — roughly half a minute of riding at
25 km/h — and a 60 km radius fits in at most 3×3 tiles. Going finer would be false
precision: the underlying radar mosaic is nearer 1 km resolution, so zoom 9 and beyond
mostly interpolate data nobody measured, while multiplying the download.

### Hail at 55 dBZ

The top colour class of the Universal Blue palette: dark red at 54, jumping to magenta
at 55 and staying in that band to the top of the scale. That jump is the palette's own
"this is now extreme" boundary and it sits inside the 55–60 dBZ range conventionally
associated with hail.

Honest limitation: real hail detection also uses the height of the freezing level,
which a 2D reflectivity mosaic cannot provide. A very heavy warm-season downpour will
trip this too. The wording says *possible* hail and should stay that way. If it proves
too twitchy in practice, it is a one-constant change.

### Heading is smoothed as a circle, not a number

The mean of 350 and 10 degrees is 0, not 180. `HeadingTracker` averages unit vectors
over a 20-second window with a 5-second half-life, which fixes the wrap-around and
hands back a curvature measure for free: the resultant length R of those vectors is 1.0
on a straight road and collapses as the headings scatter. `1 - R` becomes the cone width
and the prediction horizon in step 4.

The recency weighting matters. Unweighted, a rider who turned 90 degrees ten seconds ago
still reads as pointing 45 degrees — half way through a turn that already finished.

On switchbacks the weighted estimate reports the most recent heading rather than
cancelling to nothing, but R collapses to about 0.33, which is what tells the rest of
the app not to trust it. That is the intended behaviour: say something, flag it as
unreliable, shorten the horizon to five minutes.

### The speed unit is decided by measurement, not assumption

The SDK never documents the unit. The evidence points to m/s: `UserProfile.weight` is
documented in kilograms regardless of display preference, Hammerhead's sample delegates
formatting to Karoo via `formatDataTypeId`, and that sample's own colour thresholds
(`< 1` red, `< 5` yellow) only make sense as m/s — 5 m/s is 18 km/h, the boundary between
pottering and riding, whereas 5 km/h is walking pace.

That is still an inference, and a magnitude check cannot rescue it: if the stream were
km/h, a rider at 20 km/h reports the number 20, which is a perfectly plausible m/s value
(72 km/h, a fast descent). The error would pass silently and every ETA would be wrong.

So `SpeedUnitCalibrator` decides by cross-checking the stream against the speed implied
by successive GPS fixes. That figure is noisy, which is why it is not used for anything
else, but it is far more than accurate enough to tell a ratio of 1.0 from 3.6. Eight
consistent votes latch the decision; it does not flip mid-ride.

### Speed comes pre-smoothed from the Karoo

`SMOOTHED_3S_AVERAGE_SPEED` rather than differencing GPS positions ourselves. Removes a
whole class of noise, worst at low speed where GPS-derived speed is least reliable.

**Open question for on-device testing:** the SDK never documents the unit for speed.
`UserProfile.weight` is documented as kilograms regardless of the rider's display
preference, which strongly suggests raw data types carry SI and `preferredUnit` governs
display only — so metres per second. But it is an inference, and if it is wrong every
ETA is out by a factor of 3.6, which looks entirely plausible on screen.
`SpeedSanity` therefore checks readings against what a bicycle can actually do and
converts if a km/h stream shows up, after five consecutive implausible readings rather
than one spike.

### Fix accuracy comes from a second stream

`OnLocationChanged` carries position and orientation but no accuracy. The
`TYPE_LOCATION_ID` data type carries `LOC_ACCURACY`. Both are collected: position from
the event so there is one authority for where the rider is, accuracy from the data type.
A 50 m fix caps confidence no matter how straight the road.

### Cell motion is a coarse-to-fine search, not brute force

Frames arrive ten minutes apart and a fast squall line covers 17 km in that time — 80
pixels at zoom 8. Searching every offset in a +-80 window over a 512x512 field is 26,000
candidates against 260,000 pixels each, far too slow to run between refreshes.

`CellMotionEstimator` searches on a pyramid instead: a wide sweep at 1/8 resolution,
then progressively finer refinements around the answer carried up from below. On
synthetic fields this recovers known shifts *exactly* in about 600 comparisons, and is
more accurate than brute force on a downsampled field, because the last level refines to
full resolution.

Sum of absolute differences rather than cross-correlation: cheaper, and less easily
dominated by a single intense core dragging the alignment towards itself.

Confidence is how much better the winning offset scores than the average offset.
Structured rain gives a sharp minimum and scores around 0.88; pure noise gives a flat
surface and scores 0.00. That is what stops a vector being invented from an empty sky.

**Screen +y is south.** Rows run downward, so the bearing conversion negates y. Getting
this backwards would invert every north-south warning — plausible-looking right up until
someone rides into the rain.

### Distance and ETA are two numbers, not one

A cell 1.2 km away moving away faster than the rider closes will never be reached. A
cell 21 km away closing head-on arrives in twenty minutes. Deriving one figure from the
other would be wrong in both directions, and the gap between them is the most
interesting thing the app can say.

### Move the sample point, not the rain

Asking "will there be rain here in twelve minutes" by advancing the whole field twelve
minutes costs a field transform per time step. Stepping the *sample point* backwards
along the cell velocity answers the same question with one lookup. Checked against
forward advection over a fifteen-minute lead: the two agree to within 13 metres, a
sixteenth of a pixel at zoom 8.

### Frame age is part of the lead time

A frame stamped six minutes ago shows where cells *were*. At 12 m/s that is already
4.3 km of drift — twenty pixels. Every lookup advects by `frame age + lead time`, never
by lead time alone. In the test scenario this is the difference between a 20-minute and
a 16.5-minute ETA; getting it wrong makes every forecast quietly, consistently late.

### The cone is sampled across its width

Nine lateral samples per time step, not just the axis. The widest cone only arises with
high curvature, which also collapses the horizon to five minutes, so the
wide-and-distant combination that would leave large gaps between samples cannot occur.
Worst realistic spacing is about a kilometre — narrow enough that a shower cannot slip
through.

`coneCoverage` records what fraction of the cone was wet at first contact. Near 1 means
rain spans every plausible path and continuing gets you wet; a small value means only
some paths through the cone are affected, which is what the evasion logic in step 8 will
work from.

### Home changes the question

Plain evasion asks "which way is driest", and on its own that can produce advice which is
technically perfect and practically useless. In one simulated case, riding east away from
an advancing band scored **zero wet minutes for the whole hour** — flawless by that
measure, and useless, because it carried the rider steadily further from home. Staying
dry by fleeing is not a solution if you wanted to get back.

So when home is close enough to matter — within 20 km, or already being ridden towards,
or reachable inside the horizon — the question becomes "how much longer can I keep going
before I have to turn for home". That is both more useful and answerable with the same
machinery: ride the current heading for T minutes, then head home, and report the
*largest* T that still arrives dry.

The largest, not the first that works: the rider wants to know how much longer they can
carry on, not merely that turning round this instant would have done.

One scoring difference from the other options — the simulation stops on arrival. Once
you are home you are dry, and counting the rest of the hour as dry for every option would
flatten the comparison and make a distant turnaround look as good as an immediate one.

Home advice takes the line ahead of a plain detour. Told "head NE to stay dry" when NE is
away from home, the rider has been given an answer to a question they did not ask.

### The pipeline

Two loops on different clocks. Radar is polled every three minutes, because RainViewer
publishes a new frame every ten and refreshing faster only re-downloads the same picture.
The forecast is recomputed every thirty seconds, because the *rider* moves between frames
and their ETA changes even when the weather does not — and recomputing is free once the
field is decoded.

`RadarRepository` is the only part of RainCall that touches the network or Android
graphics. Two things there are easy to get wrong: tiles are decoded as `ARGB_8888`
because a 565 config would quantise the grey channel and destroy the linear dBZ encoding,
and a missing tile leaves a `NO_DATA` hole rather than abandoning the frame — the rest of
the mosaic is still worth having, and the hole must not read as clear sky. If a tile ever
comes back in something other than colour scheme 0, the repository notices it is not
greyscale and switches to the Universal Blue fallback rather than decoding nonsense
confidently.

### Extras

**Wet roads.** A road stays slippery after the rain ends, and the moment of highest risk
is often the first dry corner — the rider has stopped thinking about rain and the surface
has not caught up. The drying window scales with the worst intensity seen, and the
wording says "likely" because temperature and sun matter as much and we have neither.

**Post-ride summary.** Minutes wet, worst intensity, and how often the warnings came
true. Not for deciding anything mid-ride; its value is over time, because a rider who can
see the forecast was right the last five times will believe the sixth. The accuracy
figure is reported plainly including when it is poor — a tool that only reports its
successes is not worth believing about anything.

**Stopped mode** needed no special code in the end. Standing still there is no heading to
project along, so the forecaster already declines to project and the message degrades to
distance and bearing on its own.

**Snow** came free from the colour scheme 0 encoding, as noted above.

### Evasion is a judgement problem, not an arithmetic one

Four families of option — continue, reverse, wait, detour in eight directions — all
scored by the same measure: minutes of the next hour spent wet. Roughly 1,300 field
lookups per update, well inside what the Karoo can do between refreshes.

The arithmetic is the easy part. It is trivially easy to build something that always
recommends a detour, because some direction is always marginally drier, or one that
never does. Two rules keep it honest:

- An alternative must save at least **five wet minutes** before it is suggested.
  Sending someone off their route to save thirty seconds is worse than saying nothing.
  Tuned against simulation: a band sweeping across the route showed a detour saving
  1.5 minutes, a band squarely ahead showed 16.5.
- A **wait must repay the time it costs** — at least half a wet minute per minute stood
  still. Waiting twenty minutes to dodge six is a slower way to finish the ride.

And when nothing works, it says so. Being told to divert into 30 minutes of rain
instead of 35 is not advice; an app that always finds a solution stops being believed
the first time the solution is wrong.

**A design flaw this found.** The first version scored a detour purely by angular
deviation, which made every detour cheaper than any wait. Across 320 simulated cells,
the "wait" branch never won once — a whole option family was dead code that looked
fine. The weights were wrong about what a rider actually pays: a detour abandons the
route for the rest of the hour, while a wait costs ten minutes and leaves them exactly
where they meant to be. Re-weighted, all five verdicts are reachable, and waiting wins
in the situation it should — a compact cell crossing the route just ahead:

| Verdict | Share of 320 simulated cells |
| --- | --- |
| detour | 128 |
| reverse | 85 |
| continue | 42 |
| no rain ahead | 26 |
| no good option | 22 |
| wait | 17 |

Advice comes out as one of a small set of fixed phrasings rather than generated prose.
On a screen read at speed, a rider who has seen the same handful of phrasings before can
parse them at a glance; novelty costs comprehension and buys nothing. Only *actionable*
advice reaches the full-screen alert — "no better route" is worth a line in the field,
which the rider chose to look at, but not worth the interruption they did not.

### Interrupting rarely is the whole design

Deciding that rain is coming is easy; the field already says so. The hard part is
interrupting seldom enough that the interruption still means something. An ETA
oscillates as cells move and each frame lands, so the naive rule "warn below ten
minutes" fires every time it wobbles across the line.

Four mechanisms, and the numbers below come from simulating whole rides rather than
from reasoning about the rules:

| Ride | Alerts |
| --- | --- |
| Steady approach over 40 minutes | 3 — one per threshold |
| ETA wobbling around 10 min for half an hour | 1 |
| ETA jumping 22 → 4 min between frames | 1, not 3 |
| Light turning heavy then hailing | 4, each genuinely new |
| Already raining when the app starts | 0 |
| Two hours of flickering light rain | 3 |

Thresholds (20, 10, 5 min) fire once per episode. A cooldown floors the gap between
alerts. Escalation — heavier rain, or hail appearing — is allowed to interrupt sooner,
because it is new information. An episode only re-arms after ten sustained clear
minutes, so a cell edge wobbling out of the cone for one frame does not let a single
shower warn three times.

Rain already falling when the app starts is not announced: telling someone riding
through rain that it is raining is not a warning, it is an observation they made some
minutes ago.

The policy is deliberately free of Android types so it can be simulated over a whole
ride in a test. `AlertPresenter` does the platform side separately.

**A bug this found:** the first version used `0` as the sentinel for "no alert has
fired". Zero is a valid timestamp, so the cooldown was silently inoperative whenever the
clock read zero — invisible in production, but it made the simulation lie about the
escalation case. Now nullable.

### One data type, not three sizes

Karoo passes the real pixel dimensions of whichever slot the rider chose. So there is
one data type, and `TextFitter` derives font size and line count from those dimensions.
Three separate small/medium/large types would clutter the field picker with three
entries for one piece of information, and still would not adapt to a resized slot.

The font is kept as large as the *full* headline allows, then whatever vertical room is
left goes to detail. Legibility on a moving bike comes first. There is a floor below
which we do not shrink: illegible text is no better than none.

### Text is measured, not counted

`Paint.measureText` against the real field width, not a character count estimated from
the font size. Glyph widths are not uniform — "Rain in 12 min" and "WWWW WW WW WWW" are
the same character count and nowhere near the same width — so counting is exactly how
text that passes in testing overlaps on the device. The measurement is injected as a
function so the fitting logic is testable on the JVM, and the test's stand-in is
proportional rather than monospace for the same reason.

Each `Text` is capped at one line, because the fitter has already broken the message
into lines that fit. Letting Glance wrap would undo that work, which is how text ends up
overlapping in the first place.

### Details degrade rather than disappear

Checked against realistic Karoo slots, the first version silently dropped
"moderate, ~20 min" while keeping "possible hail" — losing the *more* important fact
purely because it happened to be wider. Every detail now carries a shorter wording to
fall back on ("moderate", "hail?", "3 km NW"), so intensity survives even in a
quarter-width field.

### Text fits by construction, not by hope

Karoo reports the real size of a data field at runtime through `ViewConfig.viewSize`,
and `gridSize` as a column/row span out of a total of 60. So rather than guessing at
three fixed size classes, `MessageComposer` keeps the message as separate facts and
`render(maxLines, maxChars)` assembles the longest version that fits the space actually
available.

A detail that does not fit is dropped entirely, never truncated: a half-word read at
30 km/h is worse than no word. Headlines step down a ladder — "Rain in 12 min", then
"Rain 12m", then "12m" — because cutting "Rain 12m" to six characters gives "Rain 1",
which does not merely look bad, it reads as one minute. A misleading number is worse
than a terse one.

Verified exhaustively: 1,075,200 combinations of type, intensity, ETA, duration, hail,
nearest cell, confidence, units, width and line count were rendered, with zero
overflows. Truncation is unreachable at three characters or more.

### Wording rules

- **Hail is always "possible".** A 2D mosaic cannot see the freezing level, so a heavy
  warm-season downpour trips the same threshold. Stating it as fact would overclaim.
- **Confidence is in the message**, not hidden. High confidence gets no qualifier at
  all — adding "likely" everywhere would drain the word of meaning when it matters.
- **Empty categories are omitted.** No hail, no hail word. Showing "hail: no" would
  waste the most valuable line on the screen.
- **"No data" never renders as "clear".** Not seeing rain and seeing no rain are
  different, and only one means it is safe to carry on.

### Clutter floor at 15 dBZ

Radar routinely sees insects, dust and ground clutter below this. Reporting it as rain
would mean warning about precipitation that does not exist.

### What is deliberately missing

Lightning. It cannot be inferred from reflectivity, and every usable source needs a
paid or key-bearing API — which means either a server or a key sitting in the APK.
Neither fits a device-only build. Everything else from the v2/v3 plans is in scope.

---

## Repository setup

One secret is needed before CI will build:

- `KAROO_EXT_TOKEN` — a **classic** personal access token with the `read:packages`
  scope.

The `karoo-ext` SDK is published to GitHub Packages, and GitHub Packages requires
authentication for Maven reads even when the package is public. The built-in
`GITHUB_TOKEN` cannot read packages from another organisation's repository, so it has
to be a PAT. The failure mode when this is missing looks like "artifact not found"
rather than "unauthorised", which is worth knowing before losing an afternoon to it.

There is no committed Gradle wrapper. CI installs Gradle directly
(`gradle-version` in the workflow), so nothing needs to be built locally.

---

## Running the tests

```
gradle test
```

Expected values throughout were computed against independent reference models — the Web
Mercator formulas, RainViewer's published colour table, a separate implementation of the
circular-statistics weighting — rather than produced by running this code. A test that
only confirms the code agrees with itself would pass happily with the projection
inverted.

Every `karoo-ext` symbol used has been checked against the v1.1.9 source rather than
recalled: `OnLocationChanged.orientation` is nullable, `LOC_ALTITUDE` and `LOC_SPEED`
appear in the LOCATION doc comment but are not declared as `Field` constants, and the
repository's default branch is `master`, not `main`.
