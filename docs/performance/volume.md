# Documentação do Volume de Treino

## 1. Conceito e Definição

O **Volume de Treino** no aplicativo mensura a quantidade de carga total movimentada pelo usuário em um dado período ou sessão. É a métrica fundamental de tonelagem para avaliar o estresse mecânico e o progresso ao longo do tempo.

---

## 2. Fórmula Oficial de Cálculo

Para cada série individual executada e concluída:

$$\text{Volume da Série} = \text{Peso (kg)} \times \text{Repetições}$$

---

## 3. Regras e Filtros

1. **Séries Válidas**:
   - Apenas séries marcadas como concluídas (`completed = true`) são contabilizadas.
   - Séries de aquecimento (`SetType.WARMUP`) são desconsideradas no cálculo do volume de tonelagem efetiva de trabalho.

2. **Exercícios de Peso Corporal e Cargas Nulas**:
   - Exercícios executados apenas com o peso do próprio corpo ou sem carga preenchida (`weight = 0` ou `weight = null`) geram volume $0\text{ kg}$.
   - Não há invenção ou suposição arbitrária de valores para máquinas/exercícios sem carga informada.

3. **Exemplo de Cálculo**:
   - **Supino Reto**:
     - Série 1: $60\text{ kg} \times 10 = 600\text{ kg}$
     - Série 2: $60\text{ kg} \times 10 = 600\text{ kg}$
     - Série 3: $60\text{ kg} \times 8 = 480\text{ kg}$
     - **Volume Efetivo do Exercício**: $1.680\text{ kg}$

---

## 4. Tipos Oficiais de Volume no Domínio

Para evitar ambiguidade visual na interface, o aplicativo divide o conceito de volume em três entidades oficiais:

1. `WorkoutVolume`:
   - Volume total movimentado em uma **única sessão** de treino específica.
   - Exemplo: *Treino de Peito: 12.500 kg*.

2. `WeeklyVolume`:
   - Volume total movimentado nos **últimos 7 dias** (ciclo semanal corrente).
   - Exemplo: *Volume semanal: 42.800 kg*.

3. `TotalVolume`:
   - Volume **acumulado histórico** em toda a jornada do usuário.
   - Exemplo: *Volume acumulado: 850.000 kg movimentados*.

`VolumeSummary`:
Data class que consolida as três variações (`sessionVolume`, `weeklyVolume`, `totalVolume`) para consumo otimizado pela camada de UI.

---

## 5. Arquitetura do Componente

```
WorkoutDao / Database
       │
       ▼
PerformanceRepository
       │
       ▼
VolumeCalculator (domain/performance/calculator/VolumeCalculator.kt)
       │
       ▼
VolumeSummary / WorkoutVolume
       │
       ▼
UI (Home, Performance, Evolução, Timeline)
```

---

## 6. Limitações Atuais

- Exercícios de calistenia pura sem peso adicional registrado contribuem com 0 kg para o cálculo de tonelagem.
- Futuras atualizações poderão permitir a inclusão opcional do peso corporal configurado no perfil do usuário para cálculo de volume relativo.
