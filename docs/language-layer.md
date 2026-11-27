# Language layer: the model-backed version (design 3.3, issue #20)

`LanguageLayer` (`core/lang`) is the boundary between the owner's words and the body: `interpret(message, context)`
returns typed `Intent`s, `explain(result, context)` puts a terminal goal result into words. `FixedGrammar` is the
no-model implementation; `ModelLanguageLayer` is the one that asks a language model. The body cannot tell which it has.

## Where the code is

- `core/lang/model/` (Minecraft-free: JDK, Gson and `core` only; plain JUnit in `src/test/java/.../core/lang/model/`):
  - `ModelSettings`: provider (`none`, `openai`, `anthropic`), base URL, model, key, request timeout, round-trip cap
    (default 4), output-token cap (default 4096). `toString` never prints the key; `redact(text)` scrubs it.
  - `ModelSettingsFile`: the config file's text to `ModelSettings`, and the commented default written when it is missing.
  - `ChatBackend` (one round trip: messages + tool specs in, text + tool calls out) with `OpenAiChatBackend` and
    `AnthropicChatBackend`; `JsonPost` is their shared async POST with a hard deadline.
  - `ModelLanguageLayer`, `ToolSchemas` (the seven tools), `GoalArguments` (the gate on `set_goal`), `ModelPrompts`
    (the two system prompts), `ResultText` (goal and failure wording, the built-in sentences).
- `game/lang/ModelLanguageConfig.load(Path configDir)`: reads `megablockbot/language.json` under the loader's config
  directory. It registers nothing; the bot runtime that holds the active layer builds
  `new ModelLanguageLayer(settings)` from the result (provider `none` behaves exactly as `FixedGrammar`).
- `FixedGrammar` exposes its count, level and radius limits and its equip-slot rule as public statics, so the model's
  goals are held to the same ranges and filed the same way. Change them there, not here.

## What the model is asked, and what it is not

The model is consulted at two decision points only, never per tick, and a slow or dead model never stalls the body
(the futures complete on the HTTP client's threads):

1. **Goal intake** (`interpret`). The grammar goes first: when it understands the whole message (no `Unknown` among
   its intents) those intents are the answer and no request is made. Otherwise the model gets the message with a short
   system prompt (who it is, what the tools mean, the bot's status paragraph, the dimension, the owner's and the bot's
   positions, the blueprint names, the enchantment ids the game knows, the limits) and these tools:

   | Tool | Arguments | Becomes |
   |---|---|---|
   | `set_goal` | `kind` plus that kind's fields (below) | `SetGoal(goal)`, only after every check passes |
   | `cancel` | `all` (optional) | `Cancel(all)` |
   | `status`, `pause`, `resume` | none | `Status`, `Pause`, `Resume` |
   | `search_items` | `query`, `limit` | answered locally from `context.items()` and sent back to the model |
   | `say` | `text` | `Say(text)` |

   A plain text answer with no tool call is a `Say`. Text that comes with tool calls is dropped (the prompt says to
   use `say`). Calls become intents in the order made.
2. **Failure explanation** (`explain`). For a failed, partial or cancelled goal the model is given the typed result as
   lines of facts (goal, status, matched of required, unmet keys, the failure kind and every one of its fields; no
   exception message or stack trace) and asked for one or two plain sentences, with no tools. A satisfied goal needs
   no model. If the call fails or the answer is empty the owner gets a built-in sentence, one per `Failure.Kind`
   (`ResultText.builtIn`; the `switch`es there are exhaustive over the sealed types, so a new kind will not compile
   until it has one).

It is not asked to plan (the planner expands goals), to choose strategies (`choose_strategy` is a later issue), or to
act: it never touches the world, only proposes tool calls that the checks then accept or refuse.

### `set_goal` kinds and their checks

One flat object with a `kind` rather than a union per goal: small local models and grammar-constrained servers cope
far better. `GoalArguments.check` is the only place model-named values become a `Goal`.

| `kind` | Fields | Checks |
|---|---|---|
| `have_item` | `item`, `count` (default 1), optional `enchantment` + `level` (default 1) | item must exist (`ItemSearch.byId`); count 1 to 10000; enchantment (bare ids get `minecraft:`) must be in `context.enchantments()` unless that set is empty; level 1 to 255; `level` without `enchantment` is refused |
| `tool_tier` | `tool_class`, `tier` | names of `ToolClass` / `ToolTier`, case-insensitive |
| `equip` | `items` (or one `item`) | each must exist; filed by `FixedGrammar.slotFor`; two in one slot is refused |
| `stockpile` | `item`, `count`, `position` | item exists; count required, 1 to 10000; position `{x, y, z}` whole numbers |
| `build` | `blueprint`, `position` | blueprint in `context.blueprints()` ignoring case and extra spaces (the goal carries the listed spelling) |
| `go_to` | `position` or `to_owner`, optional `radius` | radius 0 to 256; default 2 for a position, 3 for the owner |

A call that fails a check, names an unknown tool, or has arguments that are not a JSON object is sent back to the model
once as a tool error (`is_error` on Anthropic, the text on OpenAI) saying what was wrong, so it can `search_items` and
correct itself. If the model's next calls fail too, each failing call becomes an `Unknown` the owner can read. The
correction budget is per message and per round: the first round with a refusal is sent back, later refusals are final.
A call that passed stays accepted even when another call in the same reply failed (a corrected call then comes after the
ones that passed, so order can differ from the owner's in that case). A repeat of an accepted goal is acknowledged to
the model and not queued twice.

### Budgets and failure

- At most `maxRoundTrips` requests per message (default 4): each `search_items` answer or correction is another trip.
  When the cap ends a model that is still asking, the result is what the grammar said plus a note.
- A timeout (`timeoutSeconds`, enforced on the whole exchange, not only the headers), a refused connection, a non-2xx
  answer, a body that is not JSON or not shaped like the protocol's answer: `interpret` completes with what the grammar
  said (its `Unknown`, and any clauses it did understand) and one line added to the first `Unknown` that the model could
  not be reached. Goals the model had already given and the checks had passed are kept if a later trip fails.
  `interpret` never throws and `explain` never fails.
- With provider `none` the layer is the grammar and `explain` returns nothing, as `FixedGrammar` does.

## Pointing it at a server

The file is `<config dir>/megablockbot/language.json` (server-side; nothing in it is sent to clients). When it is
missing the bot writes a commented default with provider `none`. Comments (`//`) are allowed. A file that cannot be
used (invalid JSON, an unknown setting, a missing key) is logged without its values and means provider `none`: a typo
never stops the bot and never turns a model on.

| Setting | Meaning |
|---|---|
| `provider` | `none` (default), `openai`, `anthropic` |
| `baseUrl` | `openai`: the server's `/v1` address; `anthropic`: empty means `https://api.anthropic.com` (a base ending in `/v1` is accepted) |
| `model` | the model name; `anthropic` defaults to `claude-sonnet-5-5` (`claude-haiku-5-5` is the faster choice) |
| `apiKeyEnv` | name of an environment variable holding the key (preferred) |
| `apiKey` | the key itself; used when `apiKeyEnv` is empty or the variable is not set |
| `timeoutSeconds` | per request, 1 to 600, default 30 |
| `maxRoundTrips` | per owner message, 1 to 20, default 4 |
| `maxOutputTokens` | per reply, default 4096: a model that thinks first spends part of it on thinking, so keep it generous |

Examples (all with `"provider": "openai"` unless said):

- llama.cpp `llama-server --jinja`: `"baseUrl": "http://localhost:8080/v1"`, any `model`. Tool calls need `--jinja`
  (the server's own chat template handling) and a model trained for tools.
- Ollama: `"baseUrl": "http://localhost:11434/v1"`, `"model": "<a tool-capable model you pulled>"`.
- LM Studio: `"baseUrl": "http://localhost:1234/v1"`, the model name shown in its server tab.
- A hosted OpenAI-compatible gateway: its `/v1` address, a model name, and `apiKeyEnv`.
- Anthropic: `{"provider": "anthropic", "apiKeyEnv": "ANTHROPIC_API_KEY"}`.

The key is read once at load, lives only in `ModelSettings`, goes only into a request header (`Authorization: Bearer`
or `x-api-key`) and is never in a request body, a log line, `toString` or returned text. Anything that might echo it
(an error body from a gateway) goes through `ModelSettings.redact` before it can reach a log. It is never in chat.
A key sent to an `http://` address travels unencrypted: use `https://` for anything that is not your own machine or LAN.

What leaves the server: the owner's chat message, the status paragraph, the dimension, the owner's and the bot's
positions, blueprint names, enchantment ids, the item search queries the model makes and their results, and, for an
explanation, the goal result's facts. Not the inventory, the world, or other players' messages. A hosted provider
sees all of that, and bills for it. A request carries about 5,400 characters of prompt and tool definitions (1,700 of
system prompt and 3,700 of tool schemas, measured; roughly 1.5 to 2 thousand tokens, estimated), so a message that
needs a search and a goal is two requests and about 4 thousand tokens in, plus the short replies. Messages the grammar
understands cost nothing.

## Wire formats

- **OpenAI-compatible** (`OpenAiChatBackend`): `POST {baseUrl}/chat/completions`, `Authorization: Bearer` only when a
  key is set, body `model`, `max_tokens`, `messages`, `tools` as `{type: function, function: {name, description,
  parameters}}`. Tool calls are read from `choices[0].message.tool_calls` (arguments as the standard JSON string, or an
  object; a missing id is made up; content as a string or a list of text parts); a model that answers in text instead
  gives a `Say`; `<think>...</think>` left in text is stripped. Tool results go back as `role: tool` messages with
  `tool_call_id`. A `reasoning_content` the server sent is returned on that assistant turn (some hosted reasoning
  APIs refuse the next request of a tool exchange without it); a server that sends none never receives one.
  Redirects are not followed (the key travels in a header), and HTTP/1.1 is forced because the JDK client's default
  h2c upgrade offer on plain `http` does not suit every local server.
- **Anthropic native** (`AnthropicChatBackend`): `POST {baseUrl}/v1/messages`, headers `x-api-key`,
  `anthropic-version: 2026-10-21`, `content-type`. Body `model`, `max_tokens`, `system` (string), `messages`, `tools`
  as `{name, description, input_schema}`. Nothing else is sent: `temperature`, `top_p` and `top_k` are rejected on
  API version 2026-10-21, forced `tool_choice` is rejected on Sonnet 5.5, and thinking and effort are left at the
  model's defaults so the same request works on older models. The response `content` blocks are read for `text` and
  `tool_use` (`input` is an object). Results go back as the assistant's `content` **unchanged** (thinking blocks must
  return exactly as sent) followed by one user message holding a `tool_result` block per `tool_use`
  (`tool_use_id`, `content`, `is_error` on a refusal), all in that one message.
  Written from the Messages API documentation pages "Define tools" and "Handle tool calls" (platform.claude.com,
  read 2026-11-26) and the `claude-api` skill's raw-HTTP reference. **Checked against the fake server only: no
  Anthropic key was available, so the real endpoint has not been called.**

## Verification

- `./gradlew :1.21.1:test` runs `ModelLanguageLayerTest` (both wire formats against a fake server on a loopback port that
  plays back scripted answers; the layer's real HTTP client talks to it) and `ModelSettingsFileTest`. They protect that a
  model's answer can only become a goal the checks allow, and that a bad or missing model never costs the owner their
  command: no request for a grammar message, search-then-goal on both formats, each refused call sent back once and then
  `Unknown`, timeout / refused connection / HTTP 500 / non-JSON each completing with the grammar's answer, the round-trip
  cap, `explain` with a sentence and with the server down, and a scan of every log line, `toString` and returned text for
  the key (the fake echoes it back in a 401 body).
- `LiveModelProbe` is skipped unless `-Dmbb.liveProbe=<base URL>` is passed (the build forwards `mbb.*` properties to
  the test JVM): `./gradlew :1.21.1:test --tests '*LiveModelProbe*' -Dmbb.liveProbe=http://host:8000/v1
  [-Dmbb.liveProbe.model=<name>]`. It sends about twenty loosely phrased messages and writes a table (tools called,
  intents, round trips, milliseconds) to `versions/1.21.1/build/live-probe/table.md`. Run on 2026-11-26 against a LAN
  server (`qwen3.8-flash`, size and hardware not known to this project): of 20 messages, 1 was the grammar's own form (0
  trips, 1 ms), and the other 19 were loose phrasings that the model turned into the right tool call: 15 in one trip,
  3 in two (a search first, or two searches), 1 in three (a refused `set_goal` for an item that does not exist, then a
  search, then a `say`). Get, craft with a count, enchanted item and book, tier, equip a full armor set, stockpile,
  build, go somewhere, come here, cancel, status, pause, resume, a question, nonsense (a `say` asking what was
  meant), an impossible item and an impossible blueprint (each a `say` offering what exists) and a two-goal message
  all came back as the intended intents. One trip took 1.7 to 7.4 s, two or three trips 9.4 to 16.8 s. A first
  version of the prompt had the model call `status` for nonsense and drop the second goal of a two-goal message;
  the prompt now says to ask with `say` when unsure and to make one `set_goal` per goal.

## Running a model locally

The default is no model: nothing is loaded, nothing is sent, and the fixed commands (`help` lists them) work.

Memory was **not measured** for the LAN server (the probe sent it nothing but its test messages); these are general
rules, not measurements from this project. A model's weights take about (parameters x bits per weight / 8): at the
usual 4-bit quantisation roughly 0.6 GB per billion parameters, so a 4B model is about 2.5 GB, an 8B about 5 GB, a 14B
about 9 GB and a 30B class about 18 GB, plus a KV cache that grows with context (a few hundred MB at the 2 to 4 thousand
tokens one message needs) and a few hundred MB of runtime. A mixture-of-experts model needs memory for all its experts
although only some run per token. Tool calling is unreliable below roughly the 7B to 8B class. On the machine that runs
the game, the model competes for the same RAM and, if it runs on the GPU, the same VRAM as the game client; the design's
advice (section 8) stands: default to a hosted or second-box endpoint. Latency measured over the LAN (previous
section): 1.7 to 7.4 s for a message that took one trip and 9.4 to 16.8 s for two or three, which is why the body never
waits on the layer.

## Not done here / for the wiring step

- Nothing registers a layer. The runtime should call `ModelLanguageConfig.load(Platform.loader().configDir())`, build
  `new ModelLanguageLayer(settings)` once, call `interpret` off the server thread (the future completes on an HTTP
  thread: hand its result back to the server thread before touching the world), and feed terminal goal results to
  `explain`.
- Not exercised inside a running NeoForge server: the layer uses the JDK's `java.net.http` (the java.se module, in the
  boot layer) and the Gson that Minecraft bundles; the node test classpath uses Gson 2.10.1 (what 1.21.1 ships; 1.21.10
  ships 2.11.0 and 1.21.11 ships 2.13.2).
- Design 3.4 says pause and resume are "not a model tool"; the brief for #20 lists them among the model's tools, so they
  are. If that decision stands, correct design 3.3/3.4; if not, drop the two tools from `ToolSchemas`.
