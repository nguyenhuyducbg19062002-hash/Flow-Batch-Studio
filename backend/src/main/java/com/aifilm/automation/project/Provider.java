package com.aifilm.automation.project;

public enum Provider {
    /** Google Flow qua Playwright + Edge. */
    FLOW_BROWSER,
    /** Google Flow qua extension Chrome của app: extension gọi thẳng API Flow trong tab flow.google.com đã đăng nhập. */
    FLOW_EXTENSION,
    /** Imagen / Veo qua Vertex AI (API chính thức). */
    VERTEX_API,
    /** Seedance (video) / Seedream (ảnh) qua BytePlus ModelArk (API chính thức, API key). */
    SEEDANCE_API,
    /** Google AI Studio (Gemini API) với API key từ aistudio.google.com (Imagen 3 / Veo 2). */
    GOOGLE_AI_STUDIO,
    /** Driver giả để chạy thử toàn bộ vòng duyệt mà không gọi nền tảng nào. */
    FAKE
}
