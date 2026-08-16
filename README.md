# Minecraft Factory Planner

A Minecraft 1.20.1 Forge mod that plans production chains. You say what you want and how fast — "one
steel ingot a second" — and it works out which recipes, how many machines of which tier, what has to
come in from outside, what comes out spare, and the total EU/t.

It is a port in spirit of the Factorio mod **Factory Planner**, and its first target is **GregTech**
and the Star-Technology modpack.

## What it does

- **Build a factory chain.** Every ingredient on a line is clickable: click it and choose the
  recipe that you want. This is the default, because automatic selection is good on material chains
  and less reliable above them.
- **Or automatically plan a chain from a target.** Pick an item, state a rate, get the lines that make
  it automatically.
- **Three solver engines.** A single top-down pass for simple chains (Sequential engine), a linear system over the whole
  plan for chains that loop (Matrix engine), and a linear program for everything that is an inequality rather than
  an equation (Simplex engine).
- **Closes loops.** MFP tries to find loops: deliberate build chains that feed each other,
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

**`core` contains no game imports at all**. That is what lets the maths
be tested in about a second with no Minecraft on the classpath, and it means a GregTech update cannot
break the solver.

## Download

Either build from this source using gradle or choose the latest release on the right.
