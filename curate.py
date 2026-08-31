import json

with open("app/src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json", "r") as f:
    d = json.load(f)

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

for ex in d["exercises"]:
    eid = ex.get("id")
    if eid in curated_map:
        ex["exerciseDbSearch"] = curated_map[eid]["search"]
        ex["exerciseDbAliases"] = curated_map[eid]["aliases"]
    else:
        # Give fallback aliases for existing search
        search = ex.get("exerciseDbSearch")
        if search:
            if search.startswith("Dumbbell "):
                ex["exerciseDbAliases"] = [search.replace("Dumbbell ", "Barbell "), search.replace("Dumbbell ", "")]
            elif search.startswith("Barbell "):
                ex["exerciseDbAliases"] = [search.replace("Barbell ", "Dumbbell "), search.replace("Barbell ", "")]
            elif search.startswith("Cable "):
                ex["exerciseDbAliases"] = [search.replace("Cable ", "")]

with open("app/src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json", "w") as f:
    json.dump(d, f, indent=4, ensure_ascii=False)
