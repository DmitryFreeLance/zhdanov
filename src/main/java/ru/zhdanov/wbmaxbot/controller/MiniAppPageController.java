package ru.zhdanov.wbmaxbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MiniAppPageController {

    @GetMapping("/miniapp")
    public String openMiniApp() {
        return "redirect:/miniapp/index.html";
    }

    @GetMapping("/miniapp/export-helper")
    public String openExportHelper() {
        return "redirect:/miniapp/export-helper.html";
    }
}
