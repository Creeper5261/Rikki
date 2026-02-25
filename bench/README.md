# SWE-bench Lite 30题工件汇总

- subset_instances: `30`
- with_report_instances: `30`
- without_report_instances: `0`
- resolved_instances: `17`
- p2p_success_rate: `99.894%` (4694/4699)
- f2p_success_rate: `72.222%` (39/54)

说明：SWE-bench Lite 全量测试token消耗太高了，所以只采用了 30 题分层抽样（easy/medium/hard 各 10 题）进行粗略评测。该结果属于小样本实验，仅供工程参考。

### easy (7/10)
- psf__requests-3362 [FAIL]
- sphinx-doc__sphinx-8721 [PASS]
- django__django-10924 [PASS]
- pytest-dev__pytest-7432 [PASS]
- matplotlib__matplotlib-23987 [FAIL]
- scikit-learn__scikit-learn-13779 [PASS]
- pylint-dev__pylint-5859 [PASS]
- mwaskom__seaborn-3407 [FAIL]
- astropy__astropy-12907 [PASS]
- pydata__xarray-4094 [PASS]

### medium (6/10)
- matplotlib__matplotlib-24149 [PASS]
- psf__requests-1963 [PASS]
- pydata__xarray-3364 [FAIL]
- pytest-dev__pytest-9359 [PASS]
- pylint-dev__pylint-7993 [PASS]
- django__django-16595 [PASS]
- astropy__astropy-7746 [FAIL]
- sympy__sympy-17022 [PASS]
- sphinx-doc__sphinx-8627 [FAIL]
- scikit-learn__scikit-learn-10508 [FAIL]

### hard (4/10)
- pylint-dev__pylint-7228 [FAIL]
- pydata__xarray-4493 [FAIL]
- sympy__sympy-23117 [PASS]
- scikit-learn__scikit-learn-10949 [FAIL]
- psf__requests-2148 [PASS]
- astropy__astropy-14182 [FAIL]
- pytest-dev__pytest-5495 [FAIL]
- pallets__flask-4045 [FAIL]
- sphinx-doc__sphinx-10325 [PASS]
- django__django-13028 [PASS]


补充：多轮复测是由于镜像/依赖构建问题导致的，不然可能连30题都凑不满QAQ。