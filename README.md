A Minecraft bot that can be controlled by chat, using an LLM to set tasks and respond.

## Talking to the bot in your own words

Out of the box the bot understands a short list of commands. Tell it `help` in chat to see them. That works with no
setup and sends nothing anywhere.

If you would like it to understand ordinary sentences ("could you grab me a couple of stone picks", "what are you up
to?"), you can connect it to an AI model. You need one of these:

- an AI program running on your own computer or another one at home (such as llama.cpp, Ollama or LM Studio), or
- an account with an online AI service.

Then, on the machine that runs the Minecraft server:

1. Start the server once. It creates a file called `language.json` in the `megablockbot` folder inside the server's
   `config` folder.
2. Open that file in a text editor. The comments in it explain each line. Set `provider` to `openai` for a program on
   your own network, or `anthropic` for the Anthropic service, and fill in the address and the model name (or your
   key).
3. Restart the server.

Keep in mind:

- What you type to the bot, and a short description of what it is doing, is sent to the model. With an online service
  that means it leaves your server, and the service may charge for it.
- If you use an online service, put your key in the file or, better, name an environment variable that holds it. The
  file stays on the server and the bot never shows the key in chat or in its logs.
- A model running on your own computer uses a lot of memory: from a few gigabytes for a small one to well over ten for
  a good one, and more if it runs on the same graphics card as the game. A second computer or an online service avoids
  that.
- If the model is slow or cannot be reached, the bot carries on with the plain commands.

The longer technical notes are in `docs/language-layer.md`.
