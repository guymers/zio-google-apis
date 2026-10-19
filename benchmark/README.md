
`benchmark/Jmh/run -i 10 -wi 5 -f 1 -t 2 zga.benchmark.SearchGoogleAdsResponseBenchmark`

AMD Ryzen 9 9950X
```
# JMH version: 1.37
# VM version: JDK 25.0.4.1, OpenJDK 64-Bit Server VM, 25.0.4.1
Benchmark                                    Mode  Cnt     Score    Error  Units
SearchGoogleAdsResponseBenchmark.decode      avgt   10  3599.550 ±  4.817  us/op
SearchGoogleAdsResponseBenchmark.decodeJava  avgt   10  1250.222 ±  7.649  us/op
SearchGoogleAdsResponseBenchmark.encode      avgt   10  1084.164 ±  1.123  us/op
SearchGoogleAdsResponseBenchmark.encodeJava  avgt   10  1815.649 ± 30.303  us/op
```

```
# JMH version: 1.37
# VM version: JDK 25.0.4.1.1, OpenJDK 64-Bit Server VM, 25.0.4.1.1+1-jvmci-25.4-b23
Benchmark                                    Mode  Cnt     Score    Error  Units
SearchGoogleAdsResponseBenchmark.decode      avgt   10  1107.136 ± 45.546  us/op
SearchGoogleAdsResponseBenchmark.decodeJava  avgt   10  1252.276 ± 63.601  us/op
SearchGoogleAdsResponseBenchmark.encode      avgt   10  1255.213 ±  7.632  us/op
SearchGoogleAdsResponseBenchmark.encodeJava  avgt   10  1871.527 ± 15.773  us/op
```
