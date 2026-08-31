import json

with open("app/src/main/assets/com.example.data.local.AppDatabase/18.json", "r") as f:
    d = json.load(f)

d["database"]["version"] = 18

for table in d["database"]["entities"]:
    if table["tableName"] == "exercises":
        # Check if exerciseDbAliases is already there
        has_aliases = any(c["fieldPath"] == "exerciseDbAliases" for c in table["fields"])
        if not has_aliases:
            table["fields"].append({
                "fieldPath": "exerciseDbAliases",
                "columnName": "exerciseDbAliases",
                "affinity": "TEXT",
                "notNull": False
            })
            # Also update createSql
            table["createSql"] = table["createSql"].replace(")", ", `exerciseDbAliases` TEXT)")
            table["createSql"] = table["createSql"].replace("TEXT)", "TEXT )").replace(" )", ")") # fix if any

with open("app/src/main/assets/com.example.data.local.AppDatabase/18.json", "w") as f:
    json.dump(d, f, indent=4)
