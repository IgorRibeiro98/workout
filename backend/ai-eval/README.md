# `ai-eval/` — avaliação do Coach IA (T19.H4)

Como usar: [`docs/operations/AI_PROVIDERS.md`](../../docs/operations/AI_PROVIDERS.md#benchmark).

| Caminho | O que é | Commitável |
| --- | --- | --- |
| `datasets/coach-synthetic.v1.json` | 29 cenários **sintéticos** (contexto + tipo de request + expectativa opcional), no vocabulário real do app. Cada um passa pelo contrato do `POST /v1/ai/coach` e pela trava de dado identificável ao carregar. | sim |
| `provider-limits.json` | Limites de cada `provider:modelo` com `verifiedAt` e `source`. Revalidar antes de decidir. | sim |
| `reports/*.md`, `reports/*.json` | Saída do `ai:benchmark`: provider, modelo, cenário, status, etapa/regra de recusa, latência, tokens, violações. Nenhum prompt, nenhuma resposta. | sim |
| `../.private/ai-eval/` | Respostas cruas (`--save-responses`), pacotes de revisão cega, dataset real anonimizado. | **nunca** (`.gitignore`, `.dockerignore`) |

Nenhum teste, workflow ou deploy executa o benchmark: toda chamada real é um comando explícito com a
chave no ambiente. Enquanto o Zero Data Retention da Groq não estiver verificado, só o dataset
sintético vai para a Groq.
