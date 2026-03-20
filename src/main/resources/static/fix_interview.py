import os

file_path = r'd:\Practice\InterviewReview\src\main\resources\static\interview.html'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

old_str = """        const startBtn = document.querySelector('button:has(span.material-symbols-outlined[data-icon="rocket_launch"])');
        const submitBtn = document.querySelector('button:has(span.material-symbols-outlined[data-icon="send"])');
        
        const configSection = document.querySelector('section.max-w-4xl');
        const interviewSection = document.querySelector('section.max-w-5xl');
        
        const topicInput = document.querySelector('input[placeholder*="例如：高级架构师"]');
        const questionCountSelect = document.querySelector('select');
        
        const questionText = document.querySelector('h3.text-xl');
        const answerInput = document.querySelector('textarea');
        const progressTag = document.querySelector('span.bg-secondary-container');
        const scoreDisplay = document.querySelector('.text-3xl.font-extrabold');
        const feedbackText = document.querySelector('p.text-xs.leading-relaxed');
        const evidenceText = document.querySelector('p.italic');
        const evidenceSource = document.querySelector('p.text-\\[10px\\]');"""

new_str = """        const startBtn = document.querySelectorAll('button')[1];
        const submitBtn = document.querySelectorAll('button')[2];
        
        const configSection = document.querySelector('section.max-w-4xl');
        const interviewSection = document.querySelector('section.max-w-5xl');
        
        const topicInput = document.querySelector('input[placeholder*="例如：高级架构师"]');
        const questionCountSelect = document.querySelector('select');
        
        const questionText = document.querySelector('h3.text-xl');
        const answerInput = document.querySelector('textarea');
        const progressTag = document.querySelector('span.bg-secondary-container');
        const scoreDisplay = document.querySelector('.text-3xl.font-extrabold');
        const feedbackText = document.querySelectorAll('p.text-xs.leading-relaxed')[1] || document.querySelector('p.text-xs.leading-relaxed');
        const evidenceText = document.querySelector('p.italic');"""

# replace one literal backslash with two for python string literals if needed, wait, I can just use a regex
import re

content = re.sub(r"const evidenceSource = document\.querySelector\('p\.text-\\\\[10px\\]'\);", "", content)
content = content.replace("document.querySelector('button:has(span.material-symbols-outlined[data-icon=\"rocket_launch\"])')", "document.querySelectorAll('button')[1]")
content = content.replace("document.querySelector('button:has(span.material-symbols-outlined[data-icon=\"send\"])')", "document.querySelectorAll('button')[2]")
content = content.replace("const feedbackText = document.querySelector('p.text-xs.leading-relaxed');", "const feedbackText = document.querySelectorAll('p.text-xs.leading-relaxed')[1] || document.querySelector('p.text-xs.leading-relaxed');")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
