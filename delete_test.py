import os
import glob
file = 'app/src/test/java/com/example/FakeWorkoutDao.kt'
if os.path.exists(file):
    os.remove(file)
    print("Deleted FakeWorkoutDao.kt")
