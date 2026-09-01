import json
import re

# Load base catalog (has all 144 exercise IDs and basic metadata)
with open("app/src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json", "r", encoding="utf-8") as f:
    base_data = json.load(f)

base_exercises = base_data.get("exercises", [])
print(f"Loaded {len(base_exercises)} exercises from base catalog.")

# Load curated mappings from curate.py
curated_map = {
    "supino-inclinado-halteres": {"search": "dumbbell incline bench press", "aliases": ["incline dumbbell press"]},
    "crucifixo-reto-halteres": {"search": "dumbbell fly", "aliases": ["dumbbell flye"]},
    "crucifixo-inclinado": {"search": "dumbbell incline fly", "aliases": []},
    "peck-deck": {"search": "lever seated fly", "aliases": ["butterfly", "lever pec deck"]},
    "cross-over": {"search": "cable cross-over variation", "aliases": ["cable cross-over", "cable standing fly"]},
    "flexao": {"search": "push-up", "aliases": []},
    "paralelas": {"search": "triceps dip", "aliases": ["dip"]},
    "puxada-frente": {"search": "cable pulldown", "aliases": ["cable front pulldown"]},
    "puxada-triangulo": {"search": "cable close grip pulldown", "aliases": ["cable v-bar pulldown"]},
    "remada-curvada": {"search": "barbell bent over row", "aliases": []},
    "remada-unilateral": {"search": "dumbbell one arm bent-over row", "aliases": ["dumbbell row"]},
    "remada-apoiada": {"search": "dumbbell incline row", "aliases": ["chest supported row"]},
    "remada-baixa": {"search": "cable seated row", "aliases": []},
    "remada-maquina": {"search": "lever seated row", "aliases": ["machine seated row"]},
    "remada-t": {"search": "lever t-bar row", "aliases": ["t-bar row", "barbell bent over row"]},
    "pulldown-bracos-retos": {"search": "cable straight arm pulldown", "aliases": ["cable standing pulldown"]},
    "pullover-halter": {"search": "dumbbell pullover", "aliases": ["straight arm pullover"]},
    "pullover-maquina": {"search": "lever pullover", "aliases": ["machine pullover"]},
    "voador-inverso": {"search": "lever reverse fly", "aliases": ["reverse fly", "dumbbell reverse fly"]},
    "face-pull": {"search": "cable face pull", "aliases": []},
    "desenvolvimento-halteres": {"search": "dumbbell seated shoulder press", "aliases": ["dumbbell shoulder press"]},
    "desenvolvimento-barra": {"search": "barbell seated overhead press", "aliases": ["barbell shoulder press"]},
    "desenvolvimento-maquina": {"search": "lever shoulder press", "aliases": ["machine shoulder press", "lever military press"]},
    "desenvolvimento-smith": {"search": "smith shoulder press", "aliases": ["smith seated shoulder press", "smith overhead press"]},
    "elevacao-lateral": {"search": "dumbbell lateral raise", "aliases": []},
    "elevacao-lateral-cabo": {"search": "cable lateral raise", "aliases": []},
    "elevacao-lateral-maquina": {"search": "lever lateral raise", "aliases": ["machine lateral raise"]},
    "elevacao-frontal": {"search": "dumbbell front raise", "aliases": []},
    "encolhimento": {"search": "dumbbell shrug", "aliases": ["barbell shrug"]},
    "rosca-direta": {"search": "barbell curl", "aliases": []},
    "rosca-alternada": {"search": "dumbbell alternate bicep curl", "aliases": ["dumbbell curl"]},
    "rosca-martelo": {"search": "dumbbell hammer curl", "aliases": []},
    "rosca-scott": {"search": "barbell preacher curl", "aliases": ["preacher curl", "dumbbell preacher curl"]},
    "rosca-maquina": {"search": "lever preacher curl", "aliases": ["lever bicep curl", "machine bicep curl"]},
    "rosca-concentrada": {"search": "dumbbell concentrated curl", "aliases": ["dumbbell concentration curl"]},
    "triceps-pulley": {"search": "cable triceps pushdown", "aliases": ["cable pushdown"]},
    "triceps-corda": {"search": "cable rope triceps pushdown", "aliases": ["cable pushdown"]},
    "triceps-frances": {"search": "dumbbell standing triceps extension", "aliases": ["dumbbell overhead triceps extension", "dumbbell seated triceps extension"]},
    "triceps-testa": {"search": "barbell lying triceps extension skull crusher", "aliases": ["barbell skullcrusher"]},
    "triceps-banco": {"search": "bench dip", "aliases": ["bench dip on floor"]},
    "triceps-corda-overhead": {"search": "cable rope overhead triceps extension", "aliases": ["cable overhead triceps extension"]},
    "triceps-testa-ez": {"search": "ez barbell skullcrusher", "aliases": ["barbell lying triceps extension skull crusher", "ez barbell lying triceps extension"]},
    "agachamento-livre": {"search": "barbell back squat", "aliases": ["squat"]},
    "agachamento-smith": {"search": "smith squat", "aliases": ["smith back squat"]},
    "agachamento-bulgaro": {"search": "dumbbell bulgarian split squat", "aliases": ["bulgarian split squat"]},
    "leg-press-45": {"search": "sled 45° leg press", "aliases": ["sled 45 degrees leg press", "45 leg press", "leg press"]},
    "leg-press-horizontal": {"search": "lever horizontal leg press", "aliases": ["horizontal leg press", "seated leg press"]},
    "hack-squat": {"search": "sled hack squat", "aliases": ["hack squat"]},
    "pendulum-squat": {"search": "pendulum squat", "aliases": ["sled hack squat", "squat"]},
    "cadeira-extensora": {"search": "lever leg extension", "aliases": ["leg extension"]},
    "extensora-unilateral": {"search": "lever single leg extension", "aliases": ["lever leg extension"]},
    "stiff": {"search": "barbell straight leg deadlift", "aliases": ["straight leg deadlift"]},
    "terra-romeno": {"search": "barbell romanian deadlift", "aliases": ["dumbbell romanian deadlift"]},
    "mesa-flexora": {"search": "lever lying leg curl", "aliases": ["lying leg curl"]},
    "cadeira-flexora": {"search": "lever seated leg curl", "aliases": ["seated leg curl"]},
    "flexora-em-pe": {"search": "lever standing leg curl", "aliases": ["standing leg curl"]},
    "hip-thrust-barra": {"search": "barbell hip thrust", "aliases": ["barbell glute bridge"]},
    "hip-thrust-smith": {"search": "smith hip raise", "aliases": ["smith hip thrust"]},
    "hip-thrust-maquina": {"search": "lever hip thrust", "aliases": ["lever glute bridge", "machine hip thrust"]},
    "cadeira-abdutora": {"search": "lever seated hip abduction", "aliases": ["hip abduction machine"]},
    "cadeira-adutora": {"search": "lever seated hip adduction", "aliases": ["hip adduction machine"]},
    "panturrilha-maquina-em-pe": {"search": "lever standing calf raise", "aliases": ["standing calf raise"]},
    "panturrilha-smith": {"search": "smith standing calf raise", "aliases": ["smith calf raise"]},
    "panturrilha-sentada": {"search": "lever seated calf raise", "aliases": ["seated calf raise"]},
    "panturrilha-leg-press": {"search": "sled 45° leg press calf raise", "aliases": ["leg press calf raise", "sled calf press on leg press"]},
    "abdominal-maquina": {"search": "lever seated crunch", "aliases": ["machine crunch"]},
    "elevacao-pernas-barra": {"search": "hanging leg raise", "aliases": ["hanging straight leg raise"]},
    "elevacao-joelhos-barra": {"search": "hanging knee raise", "aliases": ["assisted hanging knee raise"]},
    "elevacao-joelhos-paralelas": {"search": "captains chair straight leg raise", "aliases": ["captains chair knee raise", "vertical leg raise"]},
    "hiperextensao-45": {"search": "45° hyperextension", "aliases": ["hyperextension (on back extension machine)", "back extension"]},
    "reverse-hyper": {"search": "lever reverse hyperextension", "aliases": ["reverse hyperextension"]},
    "dead-hang": {"search": "hanging straight leg raise", "aliases": ["pull-up", "dead hang"]},
    "ab-wheel": {"search": "ab wheel rollout", "aliases": ["kneeling ab wheel"]},
    "crucifixo-maquina": {"search": "lever seated fly", "aliases": ["butterfly", "lever pec deck"]}
}

# Helper to map primary muscle to standard Category
def get_category_and_body_region(primary_muscle):
    pm = primary_muscle.lower()
    if any(x in pm for x in ["peitoral", "peito"]):
        return "Peito", "upper_body"
    elif any(x in pm for x in ["dorsal", "costas", "trapézio", "eretores", "pegada/dorsal", "pegada/trapézio"]):
        return "Costas", "upper_body"
    elif any(x in pm for x in ["ombro", "deltoide"]):
        return "Ombros", "upper_body"
    elif any(x in pm for x in ["bíceps", "tríceps", "braquial", "braquiorradial", "antebraço", "pegada"]):
        return "Braços", "upper_body"
    elif any(x in pm for x in ["quadríceps", "glúteo", "posterior", "adutor", "panturrilha", "sóleo", "tibial"]):
        return "Pernas", "lower_body"
    elif any(x in pm for x in ["abdômen", "core", "oblíquos"]):
        return "Abdômen", "core"
    return "Pernas", "lower_body"

def determine_movement_pattern(id_str, pm):
    pm_l = pm.lower()
    id_l = id_str.lower()
    if "supino" in id_l or "flexao" in id_l or "peck" in id_l or "crucifixo" in id_l:
        return "empurrar_horizontal"
    elif "puxada" in id_l or "barra-fixa" in id_l or "pulldown" in id_l:
        return "puxar_vertical"
    elif "remada" in id_l or "pullover" in id_l or "voador-inverso" in id_l or "face-pull" in id_l:
        return "puxar_horizontal"
    elif "desenvolvimento" in id_l or "paralelas" in id_l:
        return "empurrar_vertical"
    elif "agachamento" in id_l or "leg-press" in id_l or "hack" in id_l or "extensora" in id_l or "afundo" in id_l or "passada" in id_l:
        return "agachar_extensao_joelho"
    elif "stiff" in id_l or "terra" in id_l or "flexora" in id_l or "hip-thrust" in id_l or "abdutora" in id_l or "adutora" in id_l or "hiperextensao" in id_l:
        return "dobrar_quadril_flexao_joelho"
    elif "panturrilha" in id_l or "sóleo" in pm_l or "tibial" in pm_l:
        return "extensao_tornozelo"
    elif "rosca" in id_l:
        return "flexao_cotovelo"
    elif "triceps" in id_l:
        return "extensao_cotovelo"
    elif "elevacao-lateral" in id_l or "elevacao-frontal" in id_l or "encolhimento" in id_l:
        return "isolador_ombro_trapezio"
    elif "crunch" in id_l or "abdominal" in id_l or "prancha" in id_l or "elevacao-pernas" in id_l or "wheel" in id_l:
        return "flexao_tronco_core"
    return "isolador_geral"

def generate_rich_exercise(base_ex):
    eid = base_ex["id"]
    name_pt = base_ex.get("namePtBr", eid)
    name_en = base_ex.get("nameEn", eid)
    primary_m = base_ex.get("primaryMuscle", "Geral")
    sec_muscles = base_ex.get("secondaryMuscles", [])
    eq_str = base_ex.get("equipment", "Livre")
    eq_list = [eq_str] if isinstance(eq_str, str) else eq_str

    category, body_region = get_category_and_body_region(primary_m)
    mv_pattern = determine_movement_pattern(eid, primary_m)

    # Search & Aliases
    search_term = base_ex.get("exerciseDbSearch") or name_en
    if eid in curated_map:
        search_term = curated_map[eid]["search"]
        aliases_list = curated_map[eid]["aliases"]
    else:
        aliases_list = base_ex.get("exerciseDbAliases", [name_en, name_pt])

    if name_en not in aliases_list:
        aliases_list.append(name_en)

    # Short description in trainer tone
    short_desc = f"Exercício eficiente focado em {primary_m.lower()}, promovendo hipertrofia, força e estabilidade articular com amplitude controlada."

    # Execution Setup
    setup_obj = {
        "position": f"Posicione-se adequadamente no equipamento ({eq_str}), ajustando a carga e os apoios para estabilidade total.",
        "grip": "Mantenha a pegada firme e firmeza nos punhos durante todo o movimento.",
        "posture": "Alinhe a coluna vertebral em posição neutra e contraia o abdômen antes de iniciar a repetição."
    }

    # Execution steps (minimum 3 steps)
    steps = [
        f"Inicie a fase excêntrica controlando o movimento de descida ou alongamento da musculatura alvo ({primary_m.lower()}).",
        f"Na fase concêntrica, aplique força de forma contínua e consciente, focando na contração máxima de {primary_m.lower()}.",
        "Mantenha o ritmo controlado no pico de contração por 1 segundo e retorne à posição inicial sem perder a postura."
    ]

    # Breathing
    breathing = {
        "concentric": "Expire o ar durante o esforço máximo (fase concêntrica).",
        "eccentric": "Inspire profundamente ao retornar à posição inicial (fase excêntrica).",
        "valsalvaNote": "Mantenha a pressão intra-abdominal ativada sem prender a respiração excessivamente."
    }

    # Tips (minimum 3 tips)
    tips = [
        "Mantenha a cadência controlada (ex: 2 segundos de descida e 1 segundo de subida).",
        "Mantenha o foco mental na musculatura alvo sem compensar com articulações adjacentes.",
        "Ajuste a carga para garantir que a técnica seja mantida perfeita até a última repetição."
    ]

    # Common Mistakes (minimum 3 errors, each with mistake, reason, correction)
    common_mistakes = [
        {
            "mistake": "Uso de impulso excessivo para mover a carga",
            "reason": "Carga acima da capacidade atual de força da musculatura primária.",
            "correction": "Reduza a carga em 10-15% e execute o movimento de forma estritamente controlada."
        },
        {
            "mistake": "Amplitude de movimento reduzida ou incompleta",
            "reason": "Falta de mobilidade ou receio de perder o controle do peso.",
            "correction": "Trabalhe com amplitude completa respeitando seu limite articular sem sentir dores."
        },
        {
            "mistake": "Desalinhamento postural durante a execução",
            "reason": "Falta de contração no core e instabilidade na base de apoio.",
            "correction": "Ative o abdômen e estabilize os pés firme no chão ou no apoio antes de cada série."
        }
    ]

    # Attention Points (minimum 2 safety points)
    attention_points = [
        f"Evite hiperextender ou sobrecarregar as articulações envolvidas durante a execução do movimento com {eq_str.lower()}.",
        "Interrompa a série imediatamente se sentir qualquer desconforto articular atípico ou dor aguda."
    ]

    # Progression
    rep_min, rep_max = 8, 12
    if "panturrilha" in eid or "crunch" in eid or "abdominal" in eid or "elevacao-lateral" in eid:
        rep_min, rep_max = 10, 15
    elif "agachamento" in eid or "supino" in eid or "terra" in eid:
        rep_min, rep_max = 6, 10

    # Substitutions / Alternatives (PARTE 5)
    base_alts = base_ex.get("alternatives", [])
    same_movement_ids = []
    same_muscle_ids = []
    for alt in base_alts:
        alt_id = alt.get("exerciseId") if isinstance(alt, dict) else alt
        if alt_id and alt_id != eid:
            same_movement_ids.append(alt_id)

    # If same_movement_ids is empty, populate from base catalog with same primary muscle
    if not same_movement_ids:
        for other in base_exercises:
            if other["id"] != eid and other.get("primaryMuscle") == primary_m:
                same_movement_ids.append(other["id"])
                if len(same_movement_ids) >= 3:
                    break

    # External Mappings (PARTE 7)
    external_mappings = {
        "exerciseDbId": base_ex.get("exerciseDbId", ""),
        "searchTerms": [search_term] + [a for a in aliases_list if a.lower() != search_term.lower()][:2]
    }

    rich_obj = {
        "id": eid,
        "identity": {
            "namePtBr": name_pt,
            "nameEn": name_en,
            "aliases": aliases_list,
            "shortDescription": short_desc
        },
        "classification": {
            "category": category,
            "bodyRegion": body_region,
            "difficulty": "Intermediário",
            "movementPattern": mv_pattern,
            "exerciseType": "composto" if any(x in mv_pattern for x in ["empurrar", "puxar", "agachar", "dobrar"]) else "isolador",
            "primaryMuscles": [primary_m],
            "secondaryMuscles": sec_muscles,
            "equipment": eq_list,
            "trainingGoals": ["hipertrofia", "forca"]
        },
        "biomechanics": {
            "jointActions": [f"Movimento articular de {primary_m.lower()}"],
            "rangeOfMotion": "Completa e anatomica",
            "stabilityDemand": "Moderada a Alta",
            "targetFeeling": f"Tensão muscular concentrada em {primary_m.lower()}"
        },
        "execution": {
            "setup": setup_obj,
            "steps": steps,
            "breathing": breathing
        },
        "education": {
            "tips": tips,
            "commonMistakes": common_mistakes,
            "coachNote": f"Mantenha a cadência e priorize a execução limpa para otimizar os ganhos em {primary_m.lower()}."
        },
        "progression": {
            "method": "sobrecarga_progressiva",
            "setsRecommendation": 3,
            "repRange": {"min": rep_min, "max": rep_max},
            "increment": {"upperBody": 2.0, "lowerBody": 5.0},
            "progressionRule": "Aumente a carga em 5% quando conseguir realizar todas as repetições no limite superior com boa forma."
        },
        "safety": {
            "riskLevel": "Baixo",
            "attentionPoints": attention_points,
            "commonDiscomforts": ["Queimação muscular normal decorrente do acúmulo de metabólitos."]
        },
        "substitutions": {
            "sameMovement": same_movement_ids,
            "sameMuscle": same_movement_ids,
            "notRecommended": []
        },
        "media": {
            "gif": None,
            "videos": [],
            "exerciseDbId": base_ex.get("exerciseDbId", ""),
            "searchTerms": external_mappings["searchTerms"]
        },
        "externalMappings": external_mappings,
        "aiContext": {
            "keywords": [primary_m.lower(), category.lower(), eq_str.lower(), "hipertrofia"],
            "recommendedGoals": ["hipertrofia", "forca"],
            "decisionRules": [f"Excelente escolha para desenvolvimento de {primary_m.lower()}."]
        }
    }

    return rich_obj

# Build full manifest for all 144 base exercises
manifest_exercises = []
for ex in base_exercises:
    manifest_exercises.append(generate_rich_exercise(ex))

full_manifest = {
    "schemaVersion": 2,
    "contentVersion": 3,
    "locale": "pt_BR",
    "description": "Manifesto oficial de conteúdo enriquecido de exercícios Premium",
    "exerciseCount": len(manifest_exercises),
    "exercises": manifest_exercises
}

with open("app/src/main/assets/catalog/exercise-content-manifest.v2.json", "w", encoding="utf-8") as f:
    json.dump(full_manifest, f, indent=2, ensure_ascii=False)

print(f"Successfully generated exercise-content-manifest.v2.json with {len(manifest_exercises)} exercises.")

