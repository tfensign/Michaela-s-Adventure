# Grok art studio

Claude writes art requests; GitHub Actions sends them to Grok (xAI image API) and saves the results here.

- `STYLE.md`: the house style, added to every request automatically
- `reference/`: existing game art Grok must match (characters, enemies, items, backgrounds)
- `requests/*.json`: one file per art job (fields documented in `tools/grok_art.py`)
- `out/<id>/`: Grok's results (`v1.png`, `v2.png`, ...), plus `done.json` (cost) or `error.json`

Setup (once): create an API key at console.x.ai, then on GitHub open the repo's
Settings > Secrets and variables > Actions > New repository secret, name it `XAI_API_KEY` and paste the key.
