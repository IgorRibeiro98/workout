# Deploy do Spark Backend por um agente (T18.3.2)

O único fluxo autorizado para um agente publicar produção é o workflow manual **Deploy Spark
Backend** (`.github/workflows/deploy-backend.yml`), disparado pelo GitHub Actions. Nada mais.

## O comando

```bash
gh workflow run deploy-backend.yml --ref main
```

Depois, acompanhe até o fim:

```bash
gh run list --workflow deploy-backend.yml --limit 1                 # pega o RUN_ID mais recente
gh run watch <RUN_ID> --exit-status
```

Se o GitHub pedir aprovação do Environment `production` (Required reviewers — ver
[`CLOUD_RUN_DEPLOYMENT.md` §20.1](./CLOUD_RUN_DEPLOYMENT.md#201-configuração-inicial-manual-setup-required)),
`gh run watch` fica parado em "waiting". Informe o usuário e espere — não existe forma de um agente
aprovar o próprio deploy.

Ao final, leia o resumo do run:

```bash
gh run view <RUN_ID> --log   # ou: abra a URL do run e leia o Step Summary
```

## Fluxo esperado do agente

```text
1. confirmar que o trabalho foi commitado/pushado em main
2. confirmar que o CI do backend (backend.yml) concluiu com sucesso naquele commit
3. disparar "Deploy Spark Backend" (gh workflow run deploy-backend.yml --ref main)
4. se o GitHub pedir aprovação do Environment:
     informar o usuário e aguardar
5. acompanhar o run até a conclusão (gh run watch --exit-status)
6. ler o Step Summary do run
7. reportar: commit, digest, revision, smoke, DR
```

Passo 1 e 2 não são um gate que o agente decide sozinho por inspeção — são o que o próprio workflow
verifica de qualquer forma (o commit precisa estar em `origin/main`, e `backend.yml` precisa ter
`completed:success` **naquele SHA exato**; ver `ops/lib.deploy-gate.sh`). Confirmar antes só evita
disparar um run que o próprio workflow vai recusar.

## Nunca faça isto para um deploy normal

Depois desta tarefa, um agente **nunca** deve, para publicar uma mudança normal de produção:

- `gcloud run deploy` / `gcloud run jobs deploy` / `gcloud run services update-traffic` direto;
- `docker push` manual para o Artifact Registry de produção;
- rodar `ops/gcp/deploy-cloud-run.sh` localmente;
- usar `SPARK_DEPLOY_ALLOW_UNVERIFIED=1` para contornar o gate de CI;
- passar `--skip-maintenance` "para ser mais rápido";
- aprovar o próprio Environment `production`;
- pedir ou usar uma chave JSON de Service Account — não existe uma para o deployer, e não deveria
  passar a existir.

Essas ações continuam existindo como **break-glass** — recuperação com o GitHub indisponível,
investigação operacional, manutenção extraordinária — nunca como atalho de conveniência. Ver
[`CLOUD_RUN_DEPLOYMENT.md` §20.4](./CLOUD_RUN_DEPLOYMENT.md#204-break-glass-deploy-local).

## O que o workflow faz (e o que ele não duplica)

`deploy-backend.yml` só fornece identidade (OIDC → Workload Identity Federation → impersonation de
`spark-github-deployer`, sem chave), ambiente controlado (`environment: production`) e orquestração
(guardas de `main`, resumo). Toda a lógica de deploy — build, push, digest, versões de secret
pinadas, gate de DR, migration, candidate/smoke, promoção de tráfego, maintenance, schedulers —
continua em `ops/gcp/deploy-cloud-run.sh`, a única autoridade. O workflow nunca reimplementa nada
disso.

## Relatar o resultado

Depois de um deploy, relate ao usuário, com a evidência do próprio run (nunca por inspeção):

- commit implantado (SHA completo);
- digest da imagem (`...@sha256:...`);
- revision do Cloud Run e percentual de tráfego;
- resultado do smoke final;
- resultado do `dr-status.sh --backups-only`;
- URL do run no GitHub Actions.

Se alguma dessas informações não puder ser confirmada pelo run (por exemplo, o run falhou antes de
chegar lá), reporte como `NOT VERIFIED` — nunca como concluído.

## Ver também

- [`CLOUD_RUN_DEPLOYMENT.md` §20](./CLOUD_RUN_DEPLOYMENT.md#20-deploy-via-github-actions-e-workload-identity-federation-t1832) — desenho completo da integração, IAM mínimo e bootstrap.
- [`OPERATIONS_CHECKLIST.md`](./OPERATIONS_CHECKLIST.md) — checklist operacional antes/depois de um deploy.
- [`SECURITY.md`](./SECURITY.md) — modelo de ameaças e por que não existe chave de Service Account aqui.
