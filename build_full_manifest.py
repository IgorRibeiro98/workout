import json
import re

with open('app/src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json', 'r', encoding='utf-8') as f:
    base_catalog = json.load(f)

with open('app/src/main/assets/catalog/youtube-exercise-videos.v1.json', 'r', encoding='utf-8') as f:
    yt_data = json.load(f)

yt_map = {}
for v in yt_data.get('videos', []):
    eid = v.get('exerciseId') or v.get('canonicalSlug')
    if eid:
        yt_map[eid] = {
            "type": "execution",
            "platform": "youtube",
            "id": v.get('youtubeVideoId')
        }

known_gifs = {
    "supino-reto-barra": "https://v2.exercisedb.io/image/0025.gif",
    "supino-inclinado-halteres": "https://v2.exercisedb.io/image/0314.gif",
    "puxada-frente-aberta": "https://v2.exercisedb.io/image/0150.gif",
    "remada-baixa-cabo": "https://v2.exercisedb.io/image/0237.gif",
    "agachamento-smith": "https://v2.exercisedb.io/image/0757.gif",
    "leg-press-45": "https://v2.exercisedb.io/image/0585.gif",
    "cadeira-extensora": "https://v2.exercisedb.io/image/0583.gif",
    "mesa-flexora": "https://v2.exercisedb.io/image/0599.gif",
    "desenvolvimento-maquina": "https://v2.exercisedb.io/image/0334.gif",
    "elevacao-lateral-halteres": "https://v2.exercisedb.io/image/0330.gif",
    "rosca-direta-barra": "https://v2.exercisedb.io/image/0031.gif",
    "triceps-corda": "https://v2.exercisedb.io/image/0201.gif",
}

def get_body_region(primary_muscle):
    pm = primary_muscle.lower()
    if any(k in pm for k in ['quadríceps', 'posterior', 'glúteo', 'panturrilha', 'sóleo', 'tibial', 'adutor', 'coxa', 'perna']):
        return 'lower_body'
    elif any(k in pm for k in ['abdômen', 'core', 'oblíquo', 'lombar', 'ereto']):
        return 'core'
    else:
        return 'upper_body'

def get_difficulty(ex):
    eq = ex.get('equipment', '').lower()
    name = ex.get('namePtBr', '').lower()
    if 'máquina' in eq or 'guiado' in eq or 'cabo' in eq or 'smith' in name:
        return 'iniciante'
    elif 'barra' in eq or 'livre' in name or 'halteres' in eq:
        if 'terra' in name or 'agachamento livre' in name or 'clean' in name or 'snatch' in name:
            return 'avançado'
        return 'intermediário'
    elif 'peso corporal' in eq:
        if 'barra fixa' in name or 'paralelas' in name or 'copenhagen' in name or 'dragon' in name:
            return 'avançado'
        return 'iniciante'
    return 'intermediário'

def get_exercise_type(ex):
    sec = ex.get('secondaryMuscles', [])
    pattern = ex.get('movementPattern', '').lower()
    if len(sec) == 0 or 'isolado' in pattern or 'flexion' in pattern or 'extension' in pattern or 'abduction' in pattern or 'adduction' in pattern or 'raise' in pattern:
        return 'isolado'
    return 'composto'

def build_premium_exercise(base):
    eid = base['id']
    name_pt = base['namePtBr']
    name_en = base.get('nameEn', name_pt)
    primary_m = base.get('primaryMuscle', 'Músculo Alvo')
    sec_m = base.get('secondaryMuscles', [])
    equip = base.get('equipment', 'Livre')
    pattern = base.get('movementPattern', 'geral')
    sub_group = base.get('substitutionGroup', '')

    body_region = get_body_region(primary_m)
    difficulty = get_difficulty(base)
    ex_type = get_exercise_type(base)

    # Aliases
    aliases = base.get('exerciseDbAliases', [])
    if name_en and name_en not in aliases:
        aliases.append(name_en)

    # Short Description
    short_desc = f"Exercício {ex_type} focado no fortalecimento e hipertrofia de {primary_m.lower()}, utilizando {equip.lower()} com padrão biomecânico de {pattern.replace('_', ' ')}."

    # Rep Range & Progression
    if body_region == 'core' or 'panturrilha' in primary_m.lower() or 'antebraço' in primary_m.lower():
        rep_min, rep_max = 12, 20
    elif ex_type == 'isolado':
        rep_min, rep_max = 10, 15
    else:
        rep_min, rep_max = 8, 12

    inc_upper = 1.25 if 'halteres' in equip.lower() or ex_type == 'isolado' else 2.5
    inc_lower = 2.5 if 'halteres' in equip.lower() or ex_type == 'isolado' else 5.0

    # Steps
    step1_title = "Preparação e Ajuste"
    step1_desc = f"Ajuste o equipamento ({equip.lower()}) e posicione o corpo de forma estável. Mantenha os pés bem apoiados, postura firme e escápulas neutras ou retraídas conforme o movimento."

    step2_title = "Execução da Fase Concêntrica"
    step2_desc = f"Inicie o movimento focando na ativação concentrada de {primary_m.lower()}. Execute a fase positiva de forma controlada sem utilizar impulso ou compensação muscular."

    step3_title = "Fase Excêntrica e Retorno"
    step3_desc = f"Retorne à posição inicial controlando o peso durante toda a fase negativa. Sinta o alongamento de {primary_m.lower()} sem perder a tensão biomecânica."

    # Breathing
    ecc_breath = "Inspire profundamente durante a fase de alongamento (excêntrica) preparando o core para a carga."
    conc_breath = "Expire de forma controlada no ponto de maior esforço (concêntrico) para estabilizar a pressão intra-abdominal."

    # Tips
    tip1 = {"title": "Controle do Tempo", "description": "Priorize 2 a 3 segundos na fase negativa (excêntrica) para otimizar o estresse mecânico e o recrutamento de fibras musculares."}
    tip2 = {"title": "Estabilidade do Core", "description": f"Mantenha a musculatura abdominal e lombar firme durante toda a amplitude para evitar compensações articulares no exercício de {primary_m.lower()}."}
    tip3 = {"title": "Amplitude Completa", "description": "Execute o movimento na máxima amplitude biomecanicamente segura para obter ativação muscular completa em cada repetição."}

    # Common Mistakes
    m1 = {"mistake": "Uso de impulso/balanço", "why": "Reduz drasticamente a tensão na musculatura alvo e transfere a carga para articulações vulneráveis.", "correction": "Reduza levemente a carga e mantenha o tronco/articulações adjacentes estáticas durante a execução."}
    m2 = {"mistake": "Amplitude incompleta ou encurtada", "why": "Limita os ganhos de hipertrofia ao negligenciar o alongamento sob tensão e o pico de contração.", "correction": "Ajuste o peso para permitir amplitude total sem perder o alinhamento corporal."}
    m3 = {"mistake": "Perda de alinhamento postural", "why": "Aumenta o risco de lesões na coluna ou ombros devido à perda da estabilização escapular/pélvica.", "correction": "Mantenha a postura neutra, escápulas ajustadas e abdominal contraído do início ao fim."}

    # Coach Note
    coach_note = f"Excelente escolha para desenvolver {primary_m.lower()}. Foque na qualidade biomecânica antes de progredir carga pesada."

    # Risk level & attention points
    if difficulty == 'avançado':
        risk_level = 'alto' if body_region in ['lower_body', 'core'] else 'médio'
    elif difficulty == 'intermediário':
        risk_level = 'médio'
    else:
        risk_level = 'baixo'

    att1 = {"title": "Manutenção da Postura Neutra", "description": "Evite flexões ou hiperextensões excessivas da coluna durante o pico de tensão da carga."}
    att2 = {"title": "Alinhamento Articular", "description": "Mantenha joelhos, cotovelos e punhos perfeitamente alinhados no plano anatômico do movimento."}

    # Substitutions
    alts = []
    for alt in base.get('alternatives', []):
        alts.append({
            "exerciseId": alt['exerciseId'],
            "reason": alt.get('reason', 'SAME_MOVEMENT_DIFFERENT_EQUIPMENT')
        })

    # Media
    gif_info = None
    if eid in known_gifs:
        gif_info = {"url": known_gifs[eid], "source": "ExerciseDB"}
    
    videos_list = []
    if eid in yt_map:
        videos_list.append(yt_map[eid])

    # AI Context
    keywords = [primary_m.lower(), equip.lower(), body_region, ex_type, difficulty]
    if sec_m:
        keywords.extend([s.lower() for s in sec_m[:2]])

    rec_goals = ["muscle_gain", "strength"] if ex_type == 'composto' else ["muscle_gain", "definition"]
    dec_rules = [f"Recomendado para treino de {primary_m.lower()}", f"Utilizado com equipamento {equip.lower()}"]

    return {
        "id": eid,
        "identity": {
            "namePtBr": name_pt,
            "nameEn": name_en,
            "aliases": aliases,
            "shortDescription": short_desc
        },
        "classification": {
            "difficulty": difficulty,
            "exerciseType": ex_type,
            "movementPattern": pattern,
            "primaryMuscles": [primary_m],
            "secondaryMuscles": sec_m,
            "equipment": [equip],
            "bodyRegion": body_region,
            "trainingGoals": ["hipertrofia", "força"]
        },
        "execution": {
            "setup": {
                "title": "Posicionamento e Ajuste Inicial",
                "description": step1_desc
            },
            "steps": [
                {"order": 1, "title": step1_title, "description": step1_desc},
                {"order": 2, "title": step2_title, "description": step2_desc},
                {"order": 3, "title": step3_title, "description": step3_desc}
            ],
            "breathing": {
                "eccentric": ecc_breath,
                "concentric": conc_breath
            }
        },
        "education": {
            "tips": [tip1, tip2, tip3],
            "commonMistakes": [m1, m2, m3],
            "coachNote": coach_note
        },
        "progression": {
            "method": "double_progression",
            "repRange": {
                "min": rep_min,
                "max": rep_max
            },
            "setsRecommendation": 3,
            "progressionRule": f"Quando alcançar {rep_max} repetições com boa forma técnica em todas as 3 séries, aumente a carga no menor incremento disponível ({inc_upper}kg para membros superiores / {inc_lower}kg para inferiores).",
            "increment": {
                "upperBody": inc_upper,
                "lowerBody": inc_lower
            }
        },
        "safety": {
            "riskLevel": risk_level,
            "attentionPoints": [att1, att2],
            "commonDiscomforts": [
                {
                    "location": "Desconforto articular pontual",
                    "possibleCause": "Carga excessiva ou erro de alinhamento anatômico",
                    "adjustment": "Reduza a carga, reavalie a pegada/base dos pés e mantenha o movimento dentro da amplitude confortável."
                }
            ]
        },
        "substitutions": {
            "alternatives": alts
        },
        "media": {
            "gif": gif_info,
            "videos": videos_list
        },
        "aiContext": {
            "keywords": keywords,
            "recommendedGoals": rec_goals,
            "decisionRules": dec_rules
        }
    }

exercises_list = []
for base_ex in base_catalog['exercises']:
    exercises_list.append(build_premium_exercise(base_ex))

manifest = {
    "schemaVersion": 2,
    "contentVersion": "2026.09",
    "locale": "pt-BR",
    "metadata": {
        "exerciseCount": len(exercises_list)
    },
    "exercises": exercises_list
}

out_file1 = 'app/src/main/assets/catalog/exercise-content-manifest.v2.json'
out_file2 = 'app/src/main/assets/catalog/exercises-premium.v2.json'

with open(out_file1, 'w', encoding='utf-8') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)

with open(out_file2, 'w', encoding='utf-8') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)

print(f"Generated manifest with {len(exercises_list)} exercises into {out_file1} and {out_file2}.")
