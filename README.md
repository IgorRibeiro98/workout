# Treino (Workout Tracker)

Um aplicativo Android nativo e local-first para acompanhamento de treinos de musculação, progressão de carga, timer de descanso com persistência e catálogo de exercícios com suporte a demonstrações ExerciseDB.

## Requisitos
- **Android Studio**: Ladybug / Jellyfish ou superior
- **JDK**: Java 17 (OpenJDK)
- **Android SDK**: Compile SDK 35, Min SDK 26

## Principais Funcionalidades
- **Fichas e Programas de Treino**: Criação e execução com controle de séries, repetições, carga, RIR/RPE e substituição de exercícios durante a execução.
- **Timer de Descanso Preciso**: Timer com suporte a persistência contra *process death*, notificações com alerta sonoro/tátil e controle de incremento (+30s / pular).
- **Catálogo Canônico & Demonstrações (ExerciseDB)**: Importação de catálogo base em português (144 exercícios) com matching determinístico e sincronização de GIFs via ExerciseDB.
- **Personalização de Exercícios (User Overrides)**: Substituição global de nomes, notas, aparelhos e fotos personalizadas mantendo rastreabilidade do exercício base.
- **Recordes Pessoais (PRs) e Volume**: Cálculo automático de 1RM, volume semanal por grupo muscular e carga máxima excluindo séries de aquecimento (*WARMUP*).
- **Histórico & Backup**: Registro com exportação e importação de dados 100% offline em formato JSON.

## Execução e Build

### Compilação do App
```bash
gradle assembleDebug
```

### Execução dos Testes Unitários e Integração
```bash
gradle :app:testDebugUnitTest
```

### Verificação Visual (Roborazzi)
```bash
gradle :app:verifyRoborazziDebug
```
