package com.nexusforge;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@AutoConfiguration
@ComponentScan({
        "com.nexusforge.log",
        "com.nexusforge.idempotent",
        "com.nexusforge.ratelimit",
        "com.nexusforge.error"
})
public class NexusForgeCoreAutoConfiguration {
}
