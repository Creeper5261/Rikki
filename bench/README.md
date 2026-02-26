# SWE-bench Lite 30题汇总 

- subset_instances: `30`
- with_report_instances: `30`
- without_report_instances: `0`
- resolved_instances: `17`
- p2p_success_rate: `99.894%` (4694/4699)
- f2p_success_rate: `72.222%` (39/54)

说明：SWE-bench Lite 全量测试 token 消耗太高了，所以只采用了 30 题抽样进行粗略评测。该结果属于小样本实验，仅供工程参考。
难度划分基于 [SWE-bench Verified](https://huggingface.co/datasets/SWE-bench/SWE-bench_Verified) 的原始人工时间估计标签，难度等级以及说明基于 [Cracking the Code: How Difficult Are SWE-Bench-Verified Tasks Really?](https://jatinganhotra.dev/blog/swe-agents/2025/04/15/swe-bench-verified-easy-medium-hard.html?utm_source=chatgpt.com)

<table>
  <thead>
    <tr>
      <th>难度等级</th>
      <th>时间区间</th>
      <th>典型特征</th>
      <th>具体题号及评测结果</th>
      <th>解决率</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><b>Easy</b></td>
      <td><code>&lt; 15 min</code></td>
      <td>一些简单的更改，例如向函数添加断言</td>
      <td>
        <ul>
          <li>pytest-dev__pytest-7432 [PASS]</li>
          <li>pylint-dev__pylint-5859 [PASS]</li>
          <li>psf__requests-1963 [PASS]</li>
        </ul>
      </td>
      <td>100% (3 / 3)</td>
    </tr>
    <tr>
      <td><b>Medium</b></td>
      <td><code>15–60 min</code></td>
      <td>需要一些思考的小改动</td>
      <td>
        <ul>
          <li>sphinx-doc__sphinx-8721 [PASS]</li>
          <li>scikit-learn__scikit-learn-13779 [PASS]</li>
          <li>pydata__xarray-4094 [PASS]</li>
          <li>pytest-dev__pytest-9359 [PASS]</li>
          <li>pylint-dev__pylint-7993 [PASS]</li>
          <li>sympy__sympy-17022 [PASS]</li>
          <li>sphinx-doc__sphinx-8627 [FAIL]</li>
          <li>psf__requests-2148 [PASS]</li>
          <li>pytest-dev__pytest-5495 [FAIL]</li>
          <li>pallets__flask-4045 [FAIL]</li>
          <li>sphinx-doc__sphinx-10325 [PASS]</li>
          <li>*psf__requests-3362 [FAIL]</li>
          <li>*mwaskom__seaborn-3407 [FAIL]</li>
        </ul>
      </td>
      <td>61.5% (8 / 13)</td>
    </tr>
    <tr>
      <td rowspan="2"><b>Hard</b></td>
      <td><code>1–4 h</code></td>
      <td>对多个函数或文件进行大量重写</td>
      <td>
        <ul>
          <li>django__django-10924 [PASS]</li>
          <li>matplotlib__matplotlib-24149 [PASS]</li>
          <li>pydata__xarray-3364 [FAIL]</li>
          <li>django__django-16595 [PASS]</li>
          <li>scikit-learn__scikit-learn-10508 [FAIL]</li>
          <li>pylint-dev__pylint-7228 [FAIL]</li>
          <li>pydata__xarray-4493 [FAIL]</li>
          <li>sympy__sympy-23117 [PASS]</li>
          <li>scikit-learn__scikit-learn-10949 [FAIL]</li>
          <li>django__django-13028 [PASS]</li>
          <li>*matplotlib__matplotlib-23987 [FAIL]</li>
        </ul>
      </td>
      <td>45.5% (5 / 11)</td>
    </tr>
    <tr>
      <td><code>&gt; 4 h</code></td>
      <td>需要大量研究和修改 100 多行代码的深奥问题</td>
      <td>
        <ul>
          <li>astropy__astropy-12907 [PASS]</li>
          <li>astropy__astropy-7746 [FAIL]</li>
          <li>astropy__astropy-14182 [FAIL]</li>
        </ul>
      </td>
      <td>33.3% (1 / 3)</td>
    </tr>
  </tbody>
</table>

*标星题目是未在 SWE-bench Verified 中找到对应题目，但是给出的猜测可能难度。*

多轮复测是由于镜像 / 依赖构建问题导致的，不然可能连 30 题都凑不满 QAQ。
