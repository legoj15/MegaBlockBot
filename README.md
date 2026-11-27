A Minecraft bot that can be controlled by chat, using an LLM to set tasks and respond.

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
