# `docs/operations/` — operação do Spark em produção (T16.8 → T18.3)

Oito documentos, cada um com uma pergunta. Eles apontam uns para os outros em vez de repetir o
mesmo comando oito vezes.

| Documento | A pergunta que ele responde |
| --- | --- |
| [CLOUD_RUN_DEPLOYMENT.md](./CLOUD_RUN_DEPLOYMENT.md) | Como coloco isso no ar no Google Cloud Run? Bootstrap, deploy, rollback, migration job, Secret Manager (versões pinadas), ADC, maintenance, Scheduler. **Topologia real desde a T18.2.** |
| [OPERATIONS_CHECKLIST.md](./OPERATIONS_CHECKLIST.md) | O que eu rodo toda semana, todo mês, antes e depois de um deploy, numa rotação de secret, num incidente? Comandos copiáveis (T18.3). |
| [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md) | O PostgreSQL morreu. O que eu faço agora? Backup independente do Neon, restore em destino limpo, ensaio, anti-ressurreição, RPO/RTO — Cloud Run (T18.3) e VPS. |
| [OBSERVABILITY.md](./OBSERVABILITY.md) | Como eu sei que algo quebrou? Eventos estruturados, heartbeat do maintenance, tamanho do banco, alertas, auditorias (T18.3). |
| [PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md) | Como coloco isso no ar numa VPS? Topologia, Docker Compose, firewall, SSH, DNS, TLS, deploy e rollback. Continua funcional; não é a topologia real. |
| [BACKUP_AND_RESTORE.md](./BACKUP_AND_RESTORE.md) | Como o banco da topologia VPS é protegido (restic off-site), e como eu o restauro? |
| [SECURITY.md](./SECURITY.md) | Quais são as ameaças, onde vivem os segredos, como rotacioná-los, qual a política de TLS do banco, a postura de dependências e o que sai do aparelho. |
| [RUNBOOK.md](./RUNBOOK.md) | Uma coisa quebrou. O que eu faço? Por sintoma — nas duas topologias. |

Os scripts da VPS estão em [`../../ops/`](../../ops/); os do Cloud Run em
[`../../ops/gcp/`](../../ops/gcp/).

## Vocabulário de estado

Estes documentos distinguem quatro estados, e a distinção não é decorativa:

| Marca | Significa |
| --- | --- |
| `IMPLEMENTED` | Existe no repositório e foi exercitado (teste automatizado ou smoke local). |
| `MANUAL SETUP REQUIRED` | Precisa de um operador humano com acesso ao provedor. Não acontece sozinho. |
| `VERIFIED` | Comprovado com evidência no ambiente real. |
| `NOT VERIFIED` | Não foi comprovado. Não é o mesmo que "não funciona" — é "ninguém provou". |

**Estado atual (T16.8 → T18.3):**

```text
CODE (VPS)                  ✅ READY        (runtime exclusivamente PostgreSQL desde a T18.0)
CODE (CLOUD RUN)             ✅ READY        (ADC, verify, ledger GCS, maintenance/scheduler — T18.2; DR, auditorias, alertas — T18.3)
CI (DOCKER + POSTGRESQL)     ✅ VERIFIED     (persistência, topologia, pg_dump sob escrita, restore drill, DR drill com anti-ressurreição, Caddy)
REAL CLOUD RUN (T18.2)       ✅ VERIFIED     (primeiro deploy, migration, health, 401, maintenance, Scheduler — revision spark-backend-00001-gs2)
REAL DR / ALERTAS (T18.3)    ver o relatório final da T18.3 — cada item é VERIFIED ou NOT VERIFIED por evidência, nunca por inspeção
REAL FIREBASE AUTH (token)   ❌ NOT VERIFIED — procedimento em OPERATIONS_CHECKLIST.md; exige conta de teste
REAL VPS / DNS / TLS         ❌ NOT VERIFIED — não há VPS provisionada (topologia alternativa)
REAL OFF-SITE BACKUP (VPS)   ❌ NOT VERIFIED — sem storage contratado (topologia alternativa)
```

Nada aqui pode dizer que um item está verificado sem a evidência correspondente.
