# Instancias CVRPLIB

`X/` contiene las 22 instancias del set X con hasta 200 nodos, cada una con su mejor solución conocida (BKS):

- `X-nN-kK.vrp`: instancia en formato TSPLIB/CVRPLIB (`EUC_2D`, depósito en el nodo 1).
- `X-nN-kK.sol`: rutas de la BKS y su costo. Los clientes van numerados desde 1 y el depósito es 0.

Las distancias se redondean al entero más cercano (`nint`), la convención de CVRPLIB para el set X.

## Origen y cita

- Set X: Uchoa, E., Pecin, D., Pessoa, A., Poggi, M., Vidal, T. y Subramanian, A. (2017). *New benchmark instances for the Capacitated Vehicle Routing Problem*. European Journal of Operational Research, 257(3), 845–858. https://doi.org/10.1016/j.ejor.2016.08.012
- Fuente original: [CVRPLIB](http://vrp.galgos.inf.puc-rio.br/).
- Copia tomada de [PyVRP/Instances](https://github.com/PyVRP/Instances) (licencia MIT, ver `LICENSE-PyVRP-Instances`).

Las BKS cambian cuando alguien encuentra una mejor solución. Si actualizas un `.sol`, anota la fecha y la fuente en el commit.

Para instancias más grandes (hasta 1.000 nodos), descarga el resto del set X desde CVRPLIB en este mismo directorio; el arnés las toma todas.
