# Benchmark do Coach — groq / openai/gpt-oss-120b

- Execução: 2026-09-25T00:13:28.976Z → 2026-09-25T00:32:36.793Z; dataset `bbda1d5067bf`.
- Respondido por: openai/gpt-oss-120b.
- Parâmetros: temperature=0.2, maxOutputTokens=3000, thinkingLevel=MEDIUM, timeoutMs=60000, dataset=coach-synthetic.v1.json, scenarios=29.
- **Veredito:** ELIGIBLE (nenhuma violação crítica aceita)

| Família | Req. | Erros provider | Estrutural válido | Semântico válido | Fim a fim | p50 ms | p95 ms | Tokens méd. | Tokens p95 | Aderência |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ANALYZE | 7 | 0 | 100.0% | 100.0% | 100.0% | 4230 | 4969 | 4212 | 7123 | — |
| GENERATE | 9 | 0 | 100.0% | 100.0% | 100.0% | 2983 | 3494 | 2779 | 3889 | 2/2 |
| ADAPT | 7 | 0 | 100.0% | 14.3% | 14.3% | 4235 | 6212 | 4154 | 5679 | 1/1 |
| EXPLAIN | 6 | 0 | 100.0% | 100.0% | 100.0% | 1442 | 2711 | 2076 | 2847 | — |
| **Total** | 29 | 0 | 100.0% | 79.3% | 79.3% | 3098 | 5290 | 3311 | 5679 | 3/3 |

Tokens por chamada — entrada: méd. 2045, p95 2879; saída (inclui raciocínio): méd. 1266, p95 2353; total: méd. 3311, p95 5679, máx. 7123.
Erros do provider: nenhum.
Recusas do validador: JSON 0, estrutura 0, semântica 6.
429 de minuto (RPM/TPM) absorvidos pela cadência, esperando o retry-after: 0.

## Capacidade estimada
- Limites (Groq Free, PRODUCTION; verificado em 2026-09-24, console.groq.com/docs/rate-limits e /docs/models): RPM 30, RPD 1000, TPM 8000, TPD 200000.
- Por chamada: p95 5679 tokens, máx. 7123. Cabe no TPM: p95 sim, máx. sim; 1 chamadas p95 por minuto.
- Diário: teto por TPD 35, seguro (80% TPD) 28, RPD 1000 → **28 chamadas/dia**.
- Quota global do Spark: 30; pico real observado: 9/dia. Resultado: **MARGINAL**.

## Cenários

| Cenário | Tipo | Status | Etapa / regra | ms | Entrada | Saída | Total | Violações | Aderência |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| analyze-sem-historico | ANALYZE | ACCEPTED | — | 1357 | 1843 | 393 | 2236 | — | — |
| analyze-evidencia-limitada | ANALYZE | ACCEPTED | — | 3808 | 2083 | 1626 | 3709 | — | — |
| analyze-historico-bom | ANALYZE | ACCEPTED | — | 4859 | 2653 | 2140 | 4793 | — | — |
| analyze-carga-estavel | ANALYZE | ACCEPTED | — | 3098 | 2068 | 1306 | 3374 | — | — |
| analyze-progressao-evidente | ANALYZE | ACCEPTED | — | 4287 | 2254 | 1790 | 4044 | — | — |
| analyze-queda-desempenho | ANALYZE | ACCEPTED | — | 4230 | 2372 | 1835 | 4207 | — | — |
| analyze-muitos-exercicios | ANALYZE | ACCEPTED | — | 4969 | 4963 | 2160 | 7123 | — | — |
| generate-hipertrofia | GENERATE | ACCEPTED | — | 2616 | 1910 | 1025 | 2935 | — | — |
| generate-forca | GENERATE | ACCEPTED | — | 3494 | 1766 | 1360 | 3126 | — | — |
| generate-duracao-curta | GENERATE | ACCEPTED | — | 2006 | 1595 | 776 | 2371 | — | — |
| generate-duracao-longa | GENERATE | ACCEPTED | — | 3447 | 2443 | 1446 | 3889 | — | — |
| generate-poucos-candidatos | GENERATE | ACCEPTED | — | 1830 | 1394 | 696 | 2090 | — | — |
| generate-muitos-candidatos | GENERATE | ACCEPTED | — | 3369 | 2043 | 1442 | 3485 | — | — |
| generate-equipamento-restrito | GENERATE | ACCEPTED | — | 2983 | 1539 | 1206 | 2745 | — | — |
| generate-candidatos-insuficientes | GENERATE | ACCEPTED | — | 674 | 1266 | 179 | 1445 | — | sim |
| generate-notes-injecao | GENERATE | ACCEPTED | — | 3092 | 1615 | 1306 | 2921 | — | sim |
| adapt-ajustar-carga | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_LOAD em [0] | 6212 | 2879 | 2800 | 5679 | — | — |
| adapt-ajustar-reps | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_REPS em [0] | 4235 | 2222 | 1671 | 3893 | — | — |
| adapt-ajustar-series | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_SETS em [0] | 3315 | 2526 | 1304 | 3830 | — | — |
| adapt-ajustar-descanso | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_SETS em [0] | 5290 | 2488 | 2353 | 4841 | — | — |
| adapt-substituir-exercicio | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a REPLACE_EXERCISE em [0] | 4067 | 2513 | 1736 | 4249 | — | — |
| adapt-sem-mudanca | ADAPT | REJECTED | SEMANTIC: campo '…' não pertence a ADJUST_REPS em [0] | 4537 | 2260 | 1934 | 4194 | — | — |
| adapt-dados-insuficientes | ADAPT | ACCEPTED | — | 1127 | 2035 | 355 | 2390 | — | sim |
| explain-recomendacao | EXPLAIN | ACCEPTED | — | 1442 | 1240 | 573 | 1813 | — | — |
| explain-treino-gerado | EXPLAIN | ACCEPTED | — | 2711 | 1732 | 1115 | 2847 | — | — |
| explain-adaptacao-carga | EXPLAIN | ACCEPTED | — | 1284 | 1609 | 448 | 2057 | — | — |
| explain-adaptacao-sem-historico | EXPLAIN | ACCEPTED | — | 1803 | 1401 | 592 | 1993 | — | — |
| explain-progresso | EXPLAIN | ACCEPTED | — | 1339 | 1275 | 495 | 1770 | — | — |
| explain-progresso-sem-treino | EXPLAIN | ACCEPTED | — | 1729 | 1308 | 665 | 1973 | — | — |
