# PowSyBl OpenLoadflow documentation

These are the documentation sources for the powsybl-open-loadflow features.

Please keep them up to date with your developments.  
They are published on [powsybl-open-loadflow.readthedocs.io](http://powsybl-open-loadflow.readthedocs.io/) and pull requests are built and previewed automatically.

## Build the documentation

When modifying the website content, you can easily preview the result on your PC.

Navigate to the "docs" directory of the project:
~~~bash
cd docs
~~~

Run the following commands:

- Install the requirements the first time
~~~bash
pip install -r requirements.txt
~~~
- Build the documentation
~~~bash
sphinx-build -a . ./_build/html
~~~
Or
~~~bash
make html
~~~
Or to build the documentation in latex format:
~~~bash
make latexpdf
~~~

### Preview the result

Then open `_build/html/index.html` in your browser.

If you want to add links to another documentation, add the corresponding repository to the `conf.py` file.
In order to automatically get the version specified in the `pom.xml`, please use the same naming as the version: if you define the
powsybl-core version with `<powsyblcore.version>`, then use `powsyblcore` as key. The specified URL should start with `https://` and end with `latest/` (the final `/` is mandatory).
For example, to add a link to the documentation of PowSyBl-Core, you need to add the following lines:
~~~python
# This parameter might already be present, just add the new value
intersphinx_mapping = {
    "powsyblcore": ("https://powsybl-core.readthedocs.io/en/latest/", None),
}
~~~

Then in your documentation file, you can add links to PowSyBl-Core documentation. If you want to link to a whole page,
use the following example:
~~~Markdown
- [load flow configuration](inv:powsyblcore:*:*#simulation/loadflow/configuration).
~~~

If you want to link a specific part of a page, use one of those examples:
~~~Markdown
- [load detail extension](inv:powsyblcore:*:*:#load-detail-extension).
~~~
*Note: for the last examples to work, there need to be a corresponding reference in the external documentation.
For those examples, `(load-detail-extension)=` has been added right before the corresponding title
in the powsybl-core documentation.

*Note²: if the build fails, try with the `-E` option to clear the cache:*
~~~bash
sphinx-build -a -E . _build/html
~~~