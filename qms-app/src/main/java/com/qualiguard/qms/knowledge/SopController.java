package com.qualiguard.qms.knowledge;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현장 표준 절차 검색 (검토 화면·MES/POP 화면에서 현장 안내를 띄울 때).
 *
 *   GET /api/qms/sop/search?q=쿠션량 감소 미성형&k=3
 */
@RestController
@RequestMapping("/api/qms/sop")
public class SopController {

    private final SopLibrary library;

    public SopController(SopLibrary library) {
        this.library = library;
    }

    @GetMapping("/search")
    public List<SopLibrary.Hit> search(@RequestParam String q, @RequestParam(defaultValue = "3") int k) {
        return library.search(q, Math.min(10, Math.max(1, k)));
    }
}
