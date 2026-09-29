# Donut Gamble

A client-side Fabric mod for Minecraft 1.21.11 that helps with the Donut SMP dispenser gamble:
you `/pay` a streamer, both dispensers fire, and the higher number wins 2x your bet. **A tie counts as a loss.**

## Install
Put `donut-gamble-1.0.0.jar` in `.minecraft/mods` along with
[Fabric API](https://modrinth.com/mod/fabric-api) and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) (1.14.1+kotlin.2.4.20 or newer).

## Build
```
./gradlew build
```
The mod jar is `build/libs/donut-gamble-1.0.0.jar`. The build also runs the JUnit tests for the math and amount parsing.

## Use
- Press **G** (rebindable under Controls → Donut Gamble) or type `/gamble` to open the GUI.
- Fill in the streamer name, min/max amounts (`500k`, `5m`, `1.5b`) and the payout multiplier.
- Log each roll by clicking your number and then the streamer's number. It's logged as soon as both are picked.
- Click **PAY SMALL** or **PAY BIG**. The recommended one is outlined green. Chat opens with `/pay <streamer> <amount>`, so you just press Enter.
  With *Send instantly* on, you confirm once and the command is sent for you.

### Commands
| Command | Does |
|---|---|
| `/gamble` | open the GUI |
| `/gamble <mine> <streamer>` | log a roll, e.g. `/gamble 7 3` |
| `/gamble undo` | remove the last roll |
| `/gamble reset` | clear all rolls |
| `/gamble set <me\|streamer> <numbers...>` | set a dispenser's contents, one number per filled slot (`clear` to go back) |

## The math
- Each dispenser has a probability for each number 1-9. It's fair (uniform) by default.
- **Learn From Rolls**: each side is estimated from its own logged rolls, starting every number at 3 pseudo-counts
  (the posterior mean of a Dirichlet(3) prior). Because the two dispensers are independent, plugging these means
  into the win formula gives the exact Bayesian expected win chance, so nothing is lost by using point estimates.
- **Manual contents** replace the estimate for that side. Each filled slot has probability 1/(filled slots).
- `win = Σ_{i>j} Pme(i)·Pstreamer(j)` and `lose = 1 − win` (ties included).
- `EV = win·(multiplier − 1) − lose` per coin. **PAY BIG** only when EV > 0.
- Fair 1-9 vs 1-9 gives win 36/81 = 44.4% and EV = −11.1%.
- **Rigging check** (20+ rolls): compares your real win rate to 36/81. It uses the *exact* binomial probability of
  winning this few times on a fair machine, rather than the normal approximation, which is inaccurate at 20-50 rolls.
  It warns when that probability is below 2.275%, the same threshold as z = −2. The z score is still shown.

Settings are saved to `config/donutgamble.json`. Rolls only last for the current game session.
