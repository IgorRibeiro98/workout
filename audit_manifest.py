import json

with open("app/src/main/assets/catalog/exercise-content-manifest.v2.json", "r", encoding="utf-8") as f:
    manifest = json.load(f)

exercises = manifest.get("exercises", [])
total_exercises = len(exercises)

approved = 0
needs_review = 0
missing_media = 0
missing_alternatives = 0
content_issues = []

local_gif_count = 0
exercisedb_count = 0
youtube_count = 0
no_media_count = 0

categories_map = {
    "Peito": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0},
    "Costas": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0},
    "Pernas": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0},
    "Ombros": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0},
    "Braços": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0},
    "Abdômen": {"total": 0, "approved": 0, "needsReview": 0, "missingMedia": 0, "missingAlternatives": 0}
}

for ex in exercises:
    eid = ex.get("id", "desconhecido")
    is_valid = True

    # Category
    cls = ex.get("classification", {})
    cat = cls.get("category", "Pernas")
    if cat not in categories_map:
        cat = "Pernas"
    
    categories_map[cat]["total"] += 1

    # Identity
    ident = ex.get("identity", {})
    if not ident.get("namePtBr"):
        content_issues.append({"exerciseId": eid, "field": "identity.namePtBr", "issueType": "MISSING_FIELD", "message": "Nome em português ausente"})
        is_valid = False
    if not ident.get("nameEn"):
        content_issues.append({"exerciseId": eid, "field": "identity.nameEn", "issueType": "MISSING_FIELD", "message": "Nome em inglês ausente"})
        is_valid = False
    if not ident.get("shortDescription"):
        content_issues.append({"exerciseId": eid, "field": "identity.shortDescription", "issueType": "MISSING_FIELD", "message": "Descrição curta ausente"})
        is_valid = False

    # Execution
    exec_info = ex.get("execution", {})
    steps = exec_info.get("steps", [])
    if not steps or len(steps) < 3:
        content_issues.append({"exerciseId": eid, "field": "execution.steps", "issueType": "INSUFFICIENT_STEPS", "message": f"Passos de execução < 3 ({len(steps)})"})
        is_valid = False

    # Education
    edu = ex.get("education", {})
    tips = edu.get("tips", [])
    if not tips or len(tips) < 3:
        content_issues.append({"exerciseId": eid, "field": "education.tips", "issueType": "INSUFFICIENT_TIPS", "message": f"Dicas < 3 ({len(tips)})"})
        is_valid = False

    mistakes = edu.get("commonMistakes", [])
    if not mistakes or len(mistakes) < 3:
        content_issues.append({"exerciseId": eid, "field": "education.commonMistakes", "issueType": "INSUFFICIENT_MISTAKES", "message": f"Erros comuns < 3 ({len(mistakes)})"})
        is_valid = False
    else:
        for m in mistakes:
            if isinstance(m, dict):
                if not m.get("mistake") or not m.get("reason") or not m.get("correction"):
                    content_issues.append({"exerciseId": eid, "field": "education.commonMistakes", "issueType": "INVALID_MISTAKE_FORMAT", "message": "Erro comum incompleto (requer erro, por que acontece e como corrigir)"})
                    is_valid = False

    # Safety
    safe = ex.get("safety", {})
    att_points = safe.get("attentionPoints", [])
    if not att_points or len(att_points) < 2:
        content_issues.append({"exerciseId": eid, "field": "safety.attentionPoints", "issueType": "INSUFFICIENT_ATTENTION_POINTS", "message": f"Pontos de atenção < 2 ({len(att_points)})"})
        is_valid = False

    # Substitutions
    subs = ex.get("substitutions", {})
    same_mv = subs.get("sameMovement", [])
    if not same_mv:
        missing_alternatives += 1
        categories_map[cat]["missingAlternatives"] += 1

    # Media Check
    media = ex.get("media", {})
    has_gif = False
    has_exercisedb = False
    has_yt = False

    gif = media.get("gif")
    if gif and isinstance(gif, dict) and gif.get("url"):
        has_gif = True
        local_gif_count += 1

    ext_map = ex.get("externalMappings", {})
    ex_db_id = ext_map.get("exerciseDbId") or media.get("exerciseDbId")
    if ex_db_id:
        has_exercisedb = True
        exercisedb_count += 1

    vids = media.get("videos", [])
    if vids:
        has_yt = True
        youtube_count += 1

    if not has_gif and not has_exercisedb and not has_yt:
        # Note: If no media explicitly set, we check search terms
        search_terms = ext_map.get("searchTerms", [])
        if search_terms:
            # ExerciseDB dynamic search available
            exercisedb_count += 1
            has_exercisedb = True
        else:
            no_media_count += 1
            missing_media += 1
            categories_map[cat]["missingMedia"] += 1

    if is_valid:
        approved += 1
        categories_map[cat]["approved"] += 1
    else:
        needs_review += 1
        categories_map[cat]["needsReview"] += 1

audit_report_json = {
    "totalExercises": total_exercises,
    "approved": approved,
    "needsReview": needs_review,
    "missingMedia": missing_media,
    "missingAlternatives": missing_alternatives,
    "mediaCoverage": {
        "totalExercises": total_exercises,
        "localGifCount": local_gif_count,
        "exerciseDbCount": exercisedb_count,
        "youtubeCount": youtube_count,
        "noMediaCount": no_media_count
    },
    "categories": [
        {"category": k, **v} for k, v in categories_map.items()
    ],
    "contentIssues": content_issues
}

with open("app/src/main/assets/catalog/premium-library-audit.json", "w", encoding="utf-8") as f:
    json.dump(audit_report_json, f, indent=2, ensure_ascii=False)

print("Audit Report Summary:")
print(f"Total Exercises: {total_exercises}")
print(f"Approved: {approved}")
print(f"Needs Review: {needs_review}")
print(f"Missing Media: {missing_media}")
print(f"Missing Alternatives: {missing_alternatives}")
print(f"Content Issues: {len(content_issues)}")

