// Common JS for Authentication and Utilities

// Intercept fetch to handle basic error routing, token logic removed for single-user mode
const originalFetch = window.fetch;
window.fetch = async function() {
    let [resource, config] = arguments;
    if (!config) {
        config = {};
    }
    
    try {
        const response = await originalFetch(resource, config);
        // If there's still a 401/403, something else might be blocking it
        if (response.status === 401 || response.status === 403) {
            console.warn('Authentication failed, but single-user mode is enabled.');
        }
        return response;
    } catch (error) {
        console.error('Fetch error:', error);
        throw error;
    }
};

function bindTokenButton() {
    const btns = document.querySelectorAll('button');
    btns.forEach(btn => {
        if (btn.innerText.includes('全局 Token 配置') || btn.textContent.includes('全局 Token 配置')) {
            btn.style.display = 'none'; // Hide the button since it's no longer needed
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    bindTokenButton();
});
