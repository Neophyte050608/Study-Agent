package io.github.imzmq.interview.interview.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendRouteController {

    @GetMapping({
            "/",
            "/monitoring",
            "/monitoring/**",
            "/notes",
            "/notes/**",
            "/coding",
            "/coding/**",
            "/profile",
            "/profile/**",
            "/interview",
            "/interview/**",
            "/knowledge",
            "/knowledge/**",
            "/practice",
            "/practice/**",
            "/ops",
            "/ops/**",
            "/settings",
            "/settings/**",
            "/workspace",
            "/workspace/**",
            "/mcp",
            "/mcp/**",
            "/intent-tree",
            "/intent-tree/**",
            "/app",
            "/app/**"
    })
    public String forwardToSpa() {
        return "forward:/spa/index.html";
    }
}




