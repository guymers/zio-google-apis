
`benchmark/Jmh/run -i 10 -wi 5 -f 1 -t 2 zga.benchmark.SearchGoogleAdsResponseBenchmark`

AMD Ryzen 9 9950X
```
# JMH version: 1.37
# VM version: JDK 25.0.4.1, OpenJDK 64-Bit Server VM, 25.0.4.1
Benchmark                                    Mode  Cnt     Score    Error  Units
SearchGoogleAdsResponseBenchmark.decode      avgt   10  1151.854 ±  2.890  us/op
SearchGoogleAdsResponseBenchmark.decodeJava  avgt   10  1262.972 ±  6.574  us/op
SearchGoogleAdsResponseBenchmark.encode      avgt   10  1066.074 ±  3.999  us/op
SearchGoogleAdsResponseBenchmark.encodeJava  avgt   10  1842.094 ± 18.310  us/op
```

```
# JMH version: 1.37
# VM version: JDK 25.0.4.1.1, OpenJDK 64-Bit Server VM, 25.0.4.1.1+1-jvmci-25.4-b23
Benchmark                                    Mode  Cnt     Score     Error  Units
SearchGoogleAdsResponseBenchmark.decode      avgt   10  1059.654 ± 113.185  us/op
SearchGoogleAdsResponseBenchmark.decodeJava  avgt   10  1263.438 ±  34.237  us/op
SearchGoogleAdsResponseBenchmark.encode      avgt   10  1050.838 ±  14.187  us/op
SearchGoogleAdsResponseBenchmark.encodeJava  avgt   10  1857.496 ±   9.542  us/op
```
