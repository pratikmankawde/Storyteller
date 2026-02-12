import fitz
doc = fitz.open(r'app\src\main\assets\demo\SpaceStory.pdf')
for i, page in enumerate(doc):
    print(f"=== PAGE {i+1} ===")
    print(page.get_text())

