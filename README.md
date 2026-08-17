# Minecraft Factory Planner

A Minecraft 1.20.1 Forge mod that plans production chains. You say what you want and how fast — "one
steel ingot a second" — and it works out which recipes, how many machines of which tier, what has to
come in from outside, what comes out spare, and the total EU/t.

It is a port in spirit of the Factorio mod **Factory Planner**, and its first target is **GregTech**
and the Star-Technology modpack.

This is **v2**. It plans, it solves, and it has a full in-game interface.

## What it does

- **Plans a chain from a target.** Pick an item, state a rate, get the lines that make it.
- **Or build the chain by hand.** Every ingredient on a line is a question: click it and choose the
  recipe that answers it. This is the default, because automatic selection is good on material chains
  and less reliable above them — and a plan you built is one you can correct.
- **Three solver engines.** A single top-down pass for simple chains, a linear system over the whole
  plan for chains that loop, and a linear programme for everything that is an inequality rather than
  an equation: machine limits, line percentages, producing at least what was asked for.
- **Closes loops.** Star-Technology deliberately builds chains that feed each other — growing a tree
  gives off the oxygen that burning charcoal needs to make the carbon dioxide the tree wants — and
  the planner will find and balance those rather than planning a second factory to supply itself.
- **Uses byproducts.** A line's leftovers are offered back to the plan, and the change is kept only
  if the plan came out no larger, no hungrier and no more expensive.
- **Understands GregTech machines.** Overclocking, parallels, multiblock structures, coil tiers and
  steam machines all feed into the rates, and the tier a plan builds at is a setting.
- **Says when it does not know.** Every derived number carries a confidence, and an assumption is
  reported rather than presented as a fact.

## Using it

In game, `P` opens the planner, or `/mfpplan 1 gtceu:steel_ingot` opens it on a solved plan.

Everything the planner does is also driven from the server console, which is how it is tested:

```
/mfp plan 1 gtceu:steel_ingot     plan a chain and print it
/mfp explain                      show the working behind every line's rate
/mfp alternatives <item>          every way to make something, ranked, with the reasons
/mfp resolve <item>               answer an import by choosing a recipe for it
/mfp defaults                     the standing preferences every plan starts from
```

## Building

```
./gradlew build            the mod jar, in forge/build/libs
./gradlew :core:test       the solver and model tests - no Minecraft, about a second
./gradlew :forge:runClient a dev client
```

Gradle must run on **JDK 17**; the path is pinned in `gradle.properties` and will need changing on
another machine. GregTech is not on a public Maven repository any more, so it is built once from a
sibling checkout of the StarT fork with `./gradlew publishToMavenLocal`. Build with
`-PwithGregTech=false` to check the mod still works without it.

## How it is put together

```
core/    the model, the recipe index, the recipe chooser and the solvers - plain Java 17
forge/   the mod: recipe providers, GregTech integration, commands and the interface
```

**`core` contains no game imports at all**, and a build task enforces it. That is what lets the maths
be tested in about a second with no Minecraft on the classpath, and it means a GregTech update cannot
break the solver. 222 tests cover it.

A few conventions run through everything: every rate is per second, recipe amounts are per craft,
machine counts stay fractional until they are displayed, energy flows through the solver as though it
were an item, and one unconvertible recipe out of tens of thousands costs that recipe and nothing
else.

## Licence

MIT, as declared in `gradle.properties`.
