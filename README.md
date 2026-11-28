A Minecraft bot that can be controlled by chat, using an LLM to set tasks and respond.

It is an extra player that lives on the server. Nobody has to keep a game window open for it, and the people playing
do not need to install anything. It plays by the same rules as everyone else: it walks, digs, crafts and fights the way
a player does, and never gets items or moves around by cheating.

## What it can do today

This is an early version, for Minecraft 1.21.1 with NeoForge.

- Make things from nothing. Told to make a stone pickaxe, it finds trees, makes planks, sticks, a crafting table and a
  wooden pickaxe, digs for stone, and makes the pickaxe. From a fresh start that takes about a minute.
- Go somewhere, or come to you.
- Cook and smelt, fish, fill buckets, milk cows and shear sheep when a recipe calls for it.
- Trade with villagers: it can sell what it gathered for emeralds and buy with them, and it knows the trick of
  giving a villager a job and trying again until the villager offers what it wants (an enchanted book, say).
- Make something and hand it to you: `Bot1, craft a quarry and give it to me`.
- Build from a plan you saved.
- Use machines from the BuildCraft mod (its assembly table) when the server has that mod.
- Look after itself: it fights or runs from monsters, eats when hungry, gets out of water before it drowns, and
  shelters for the night, digging itself in if it has nothing to build with.
- Carry on after trouble. If it dies it goes back for what it dropped. If the server restarts it picks up where it was.
- Leave your things alone. It does not break blocks that players placed, and it only opens a chest you have told it to
  use.

Not yet: farming (it picks what grows wild, but does not plant and wait), the Nether and the End, and finding
villages' and other buildings' chests. It also needs daylight and room: a long trip underground with empty hands
still goes badly.

## Trying it

1. `/aiplayer spawn Bot1` puts a bot called Bot1 into the world. It belongs to whoever spawned it, and only its owner
   (or a server operator) can tell it what to do.
2. Talk to it in chat by starting with its name: `Bot1, make 1 stone pickaxe`.
3. `Bot1, status` tells you what it is doing. `Bot1, cancel` stops it. `Bot1, help` lists everything it understands.
4. `Bot1, use chest at 10 64 -3` lets it take what it needs from that chest.

## Building it

You need Java installed. In this folder run `./gradlew buildAll` (on Windows, `gradlew.bat buildAll`). The finished mod
is the `.jar` file in `versions/1.21.1/build/libs`. Put it in the `mods` folder of a NeoForge server for Minecraft
1.21.1.

## Talking to the bot in your own words

The bot works with no AI model at all. Out of the box it understands a short list of commands (type `help` to it in
chat to see them), and nothing is sent anywhere.

To let it understand ordinary sentences ("could you grab me a couple of stone picks", "what are you up to?"), connect
it to an AI model: one running on your own computer or home network (llama.cpp, Ollama and LM Studio all work), or an
account with an online service such as Anthropic. On the machine that runs the Minecraft server:

1. Start the server once. It creates `language.json` in the `megablockbot` folder inside the server's `config` folder.
2. Open it in a text editor; the comments explain each line. Change `"provider": "none"` to `"openai"` (a program on
   your own network) or `"anthropic"`, then fill in the address and the model name.
3. The key for an online service goes in the same file, in `apiKey`. Better: put the name of a computer setting that
   holds the key (an environment variable) in `apiKeyEnv`. The file stays on the server and the bot never shows the
   key in chat or in its logs.
4. Restart the server.

Good to know:

- What you type to the bot, and a short note on what it is doing, goes to the model. With an online service that
  leaves your server, and the service may charge for it.
- A model on your own computer needs a lot of memory: a few gigabytes for a small one, well over ten for a good one.
- If the model is slow or cannot be reached, the bot carries on with the plain commands.

The technical notes are in `docs/language-layer.md`.
