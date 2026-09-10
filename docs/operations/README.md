# `docs/operations/` — operação do Spark em produção (T16.8)

Cinco documentos, cada um com uma pergunta. Eles apontam uns para os outros em vez de repetir o
mesmo comando cinco vezes.

| Documento | A pergunta que ele responde |
| --- | --- |
| [PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md) | Como coloco isso no ar? Topologia, VPS, firewall, SSH, DNS, TLS, deploy e rollback. |
| [BACKUP_AND_RESTORE.md](./BACKUP_AND_RESTORE.md) | Como o banco do servidor é protegido, e como eu o restauro? |
| [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md) | A VPS morreu. O que eu faço agora? RPO, RTO, procedimento completo. |
| [SECURITY.md](./SECURITY.md) | Quais são as ameaças, onde vivem os segredos, como rotacioná-los, qual a postura de dependências e o que sai do aparelho. |
| [RUNBOOK.md](./RUNBOOK.md) | Uma coisa quebrou. O que eu faço? Por sintoma. |

Os scripts estão em [`../../ops/`](../../ops/).

## Vocabulário de estado

Estes documentos distinguem quatro estados, e a distinção não é decorativa:

| Marca | Significa |
| --- | --- |
| `IMPLEMENTED` | Existe no repositório e foi exercitado (teste automatizado ou smoke local). |
| `MANUAL SETUP REQUIRED` | Precisa de um operador humano com acesso ao provedor. Não acontece sozinho. |
| `VERIFIED` | Comprovado com evidência no ambiente real. |
| `NOT VERIFIED` | Não foi comprovado. Não é o mesmo que "não funciona" — é "ninguém provou". |

**Estado atual (T16.8 → T18.0.2):**

```text
CODE                        ✅ READY        (runtime exclusivamente PostgreSQL desde a T18.0)
CI (DOCKER + POSTGRESQL)    ✅ VERIFIED     (persistência, topologia, pg_dump sob escrita, restore drill, Caddy)
REAL VPS / DNS / TLS        ❌ NOT VERIFIED — não há VPS provisionada
REAL POSTGRESQL GERENCIADO  ❌ NOT VERIFIED — não há Neon/banco de produção provisionado (T18.3)
REAL FIREBASE / GEMINI      ❌ NOT VERIFIED — sem credencial de produção
REAL OFF-SITE BACKUP        ❌ NOT VERIFIED — sem storage contratado
```

Nada aqui pode dizer que produção está verificada enquanto essas quatro últimas linhas não mudarem.
