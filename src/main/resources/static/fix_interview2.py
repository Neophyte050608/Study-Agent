import os

file_path = r'd:\Practice\InterviewReview\src\main\resources\static\interview.html'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# removing that line completely
content = content.replace("const evidenceSource = document.querySelector('p.text-\\[10px\\]');", "")
# if there was a single backslash:
content = content.replace("const evidenceSource = document.querySelector('p.text-\[10px\]');", "")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
