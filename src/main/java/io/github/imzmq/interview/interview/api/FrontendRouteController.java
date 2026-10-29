package io.github.imzmq.interview.interview.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendRouteController {

    @GetMapping({
            "/",
            "/chat",
            "/chat/**",
            "/notes",
            "/notes/**",
            "/profile",
            "/profile/**",
            "/learning",
            "/learning/**",
            "/admin",
            "/admin/**"
    })
    public String forwardToSpa() {
        return "forward:/spa/index.html";
    }
}




