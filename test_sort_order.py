import json

muscle_order_rank = {
    # Pernas
    'Quadríceps': 1, 'Quadríceps/Glúteos': 2, 'Posterior de coxa': 3, 'Glúteos': 4,
    'Glúteo médio': 5, 'Adutores': 6, 'Adutores/Core': 7, 'Panturrilhas': 8, 'Sóleo': 9, 'Tibial anterior': 10,
    # Peito
    'Peitoral': 20, 'Peitoral superior': 21,
    # Costas
    'Dorsal': 30, 'Costas': 31, 'Deltoide posterior': 32, 'Trapézio': 33,
    # Ombros
    'Ombros': 40, 'Deltoide lateral': 41, 'Deltoide anterior': 42,
    # Bíceps & Antebraço
    'Bíceps': 50, 'Braquial/Braquiorradial': 51, 'Braquiorradial': 52,
    # Tríceps
    'Tríceps': 60,
    # Abdômen & Core
    'Abdômen': 70, 'Core': 71, 'Oblíquos': 72, 'Eretores da coluna': 73, 'Glúteos/Lombar': 74, 'Core/Lombar': 75,
    # Antebraço / Pegada / Outros
    'Antebraço': 80, 'Pegada/Trapézio': 81, 'Pegada/Dorsal': 82, 'Pegada': 83
}

with open('app/src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json', 'r', encoding='utf-8') as f:
    base_catalog = json.load(f)

exs = base_catalog['exercises']

def sort_key(e):
    pm = e.get('primaryMuscle', '')
    rank = muscle_order_rank.get(pm, 999)
    return (rank, e['id'])

sorted_exs = sorted(exs, key=sort_key)
print(f"Total sorted: {len(sorted_exs)}")

current_group = None
for e in sorted_exs:
    pm = e.get('primaryMuscle')
    if pm != current_group:
        current_group = pm
        print(f"\n--- {current_group} ---")
    print(f"  - {e['id']} ({e['namePtBr']})")
