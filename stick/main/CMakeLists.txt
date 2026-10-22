idf_component_register(SRCS "main.cpp" "kasa.cpp" "commands.cpp"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES mbedtls esp_wifi esp_netif esp_http_client nvs_flash esp_timer)
