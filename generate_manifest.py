import json

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

# Known GIFs from ExerciseDB pilot or mapping
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

print(f"Loaded {len(base_catalog['exercises'])} base exercises.")
