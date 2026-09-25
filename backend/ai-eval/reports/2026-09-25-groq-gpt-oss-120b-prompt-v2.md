# Benchmark do Coach — groq / openai/gpt-oss-120b

- Execução: 2026-09-25T00:33:10.561Z → 2026-09-25T00:47:18.675Z; dataset `4a645a9f6677`.
- Respondido por: openai/gpt-oss-120b.
- Parâmetros: temperature=0.2, maxOutputTokens=3000, thinkingLevel=MEDIUM, timeoutMs=60000, dataset=coach-synthetic.v1.json, scenarios=20.
- **Veredito:** ELIGIBLE (nenhuma violação crítica aceita)

| Família | Req. | Erros provider | Estrutural válido | Semântico válido | Fim a fim | p50 ms | p95 ms | Tokens méd. | Tokens p95 | Aderência |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ANALYZE | 7 | 0 | 100.0% | 100.0% | 100.0% | 5264 | 6133 | 4606 | 6920 | — |
| ADAPT | 7 | 0 | 100.0% | 85.7% | 85.7% | 3187 | 4980 | 3883 | 4879 | 5/6 |
| EXPLAIN | 6 | 0 | 100.0% | 83.3% | 83.3% | 1676 | 2927 | 2108 | 2700 | — |
| **Total** | 20 | 0 | 100.0% | 90.0% | 90.0% | 3058 | 5668 | 3604 | 5424 | 5/6 |

Tokens por chamada — entrada: méd. 2273, p95 3069; saída (inclui raciocínio): méd. 1331, p95 2354; total: méd. 3604, p95 5424, máx. 6920.
Erros do provider: nenhum.
Recusas do validador: JSON 0, estrutura 0, semântica 2.
429 de minuto (RPM/TPM) absorvidos pela cadência, esperando o retry-after: 0.

## Capacidade estimada
- Limites (Groq Free, PRODUCTION; verificado em 2026-09-24, console.groq.com/docs/rate-limits e /docs/models): RPM 30, RPD 1000, TPM 8000, TPD 200000.
- Por chamada: p95 5424 tokens, máx. 6920. Cabe no TPM: p95 sim, máx. sim; 1 chamadas p95 por minuto.
- Diário: teto por TPD 36, seguro (80% TPD) 29, RPD 1000 → **29 chamadas/dia**.
- Quota global do Spark: 25; pico real observado: 9/dia. Resultado: **SUFFICIENT**.

## Cenários

| Cenário | Tipo | Status | Etapa / regra | ms | Entrada | Saída | Total | Violações | Aderência |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| analyze-sem-historico | ANALYZE | ACCEPTED | — | 1480 | 1890 | 431 | 2321 | — | — |
| analyze-evidencia-limitada | ANALYZE | ACCEPTED | — | 5668 | 2131 | 2182 | 4313 | — | — |
| analyze-historico-bom | ANALYZE | ACCEPTED | — | 6133 | 2705 | 2719 | 5424 | — | — |
| analyze-carga-estavel | ANALYZE | ACCEPTED | — | 5266 | 2115 | 2354 | 4469 | — | — |
| analyze-progressao-evidente | ANALYZE | ACCEPTED | — | 4255 | 2300 | 1770 | 4070 | — | — |
| analyze-queda-desempenho | ANALYZE | ACCEPTED | — | 5264 | 2420 | 2305 | 4725 | — | — |
| analyze-muitos-exercicios | ANALYZE | ACCEPTED | — | 4439 | 5012 | 1908 | 6920 | — | — |
| adapt-ajustar-carga | ADAPT | ACCEPTED | — | 3187 | 3069 | 1124 | 4193 | — | sim |
| adapt-ajustar-reps | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_REPS em [0] | 2617 | 2411 | 1008 | 3419 | — | — |
| adapt-ajustar-series | ADAPT | ACCEPTED | — | 3336 | 2717 | 1178 | 3895 | — | sim |
| adapt-ajustar-descanso | ADAPT | ACCEPTED | — | 4320 | 2680 | 1884 | 4564 | — | sim |
| adapt-substituir-exercicio | ADAPT | ACCEPTED | — | 4980 | 2704 | 2175 | 4879 | — | sim |
| adapt-sem-mudanca | ADAPT | ACCEPTED | — | 3058 | 2450 | 1291 | 3741 | — | não |
| adapt-dados-insuficientes | ADAPT | ACCEPTED | — | 1045 | 2225 | 267 | 2492 | — | sim |
| explain-recomendacao | EXPLAIN | ACCEPTED | — | 1471 | 1253 | 498 | 1751 | — | — |
| explain-treino-gerado | EXPLAIN | REJECTED | SEMANTIC: explanation excede 900 caracteres | 2927 | 1741 | 959 | 2700 | — | — |
| explain-adaptacao-carga | EXPLAIN | ACCEPTED | — | 1676 | 1620 | 452 | 2072 | — | — |
| explain-adaptacao-sem-historico | EXPLAIN | ACCEPTED | — | 1421 | 1411 | 515 | 1926 | — | — |
| explain-progresso | EXPLAIN | ACCEPTED | — | 2306 | 1288 | 923 | 2211 | — | — |
| explain-progresso-sem-treino | EXPLAIN | ACCEPTED | — | 1701 | 1319 | 670 | 1989 | — | — |
