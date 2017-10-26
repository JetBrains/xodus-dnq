---
layout: page
title: Table of Contents
---

Xodus-DNQ is a Kotlin library for the data definition and queries over Xodus.

{% assign relatedTopics = site.toc | where_exp:"topic","topic != 'index.md'" %}
{% for topic in relatedTopics %}
  {% assign page = site.pages | where:"path",topic | first %}
  {% assign headers = page.content | newline_to_br | strip_newlines | split:'<br />' | where_exp:"line","line contains '## '" %}
1. [{{ page.title }}]({{ page.url | relative_url }})
  {% for header in headers %}
  {% if header contains '##### '%}
            - [{{ header | remove_first:'##### ' }}]({{ page.url | relative_url }}#{{ header | slugify }})  
  {% elsif header contains '#### '%}
         - [{{ header | remove_first:'#### ' }}]({{ page.url | relative_url }}#{{ header | slugify }})  
  {% elsif header contains '### '%}
      1. [{{ header | remove_first:'### ' }}]({{ page.url | relative_url }}#{{ header | slugify }})  
  {% elsif header contains '## '%}
   1. [{{ header | remove_first:'## ' }}]({{ page.url | relative_url }}#{{ header | slugify }})  
  {% endif %}
  {% endfor %}
{% endfor %}
