package uniandes.edu.co.proyecto.servicios;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uniandes.edu.co.proyecto.modelo.Bodega;
import uniandes.edu.co.proyecto.modelo.DocumentoIngreso;
import uniandes.edu.co.proyecto.modelo.OrdenDeCompra;
import uniandes.edu.co.proyecto.modelo.Producto;
import uniandes.edu.co.proyecto.modelo.ProductoEnBodega;
import uniandes.edu.co.proyecto.modelo.ProductoPedido;
import uniandes.edu.co.proyecto.modelo.Proveedor;
import uniandes.edu.co.proyecto.modelo.Sucursal;
import uniandes.edu.co.proyecto.repositorio.BodegaRepository;
import uniandes.edu.co.proyecto.repositorio.DocumentoIngresoRepository;
import uniandes.edu.co.proyecto.repositorio.OrdenDeCompraRepository;
import uniandes.edu.co.proyecto.repositorio.ProductoEnBodegaRepository;
import uniandes.edu.co.proyecto.repositorio.ProductoPedidoRepository;

@Service
public class IngresoProductoService {

    @Autowired
    private OrdenDeCompraRepository ordenDeCompraRepository;

    @Autowired
    private BodegaRepository bodegaRepository;

    @Autowired
    private ProductoEnBodegaRepository productoEnBodegaRepository;

    @Autowired
    private ProductoPedidoRepository productoPedidoRepository;

    @Autowired
    private DocumentoIngresoRepository documentoIngresoRepository;

    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<?> registrarIngresoProductos(Integer idOrdenCompra, Integer idBodega) {
        
        //La fecha de ingreso es hoy
        LocalDate fechaIngreso = LocalDate.now();

        try {
            //¿Existe o no existe la orden de compra que mandaron?
            OrdenDeCompra ordenDeCompra = ordenDeCompraRepository.darOrdenDeCompra(idOrdenCompra);
            if (ordenDeCompra == null || !"VIGENTE".equals(ordenDeCompra.getEstado())) {
                return new ResponseEntity<>("La orden de compra no es válida para documento de ingreso de productos", HttpStatus.BAD_REQUEST);
            }

            //¿Existe o no existe la bodega que mandaron?
            Optional<Bodega> optionalBodega = bodegaRepository.findById(idBodega);
            if (optionalBodega.isEmpty()) {
                return new ResponseEntity<>("La bodega ingresada no existe", HttpStatus.BAD_REQUEST);
            }
            Bodega bodega = optionalBodega.get();

            //¿La bodega pertenece a la misma sucursal de la orden de compra?
            if (!bodega.getIdSucursal().equals(ordenDeCompra.getIdSucursal())) {
                return new ResponseEntity<>("La bodega seleccionada no hace parte de la sucursal de la orden de compra dada", HttpStatus.BAD_REQUEST);
            }

            //Obtener proveedor y sucursal como "objetos"
            Proveedor proveedor = ordenDeCompra.getNitProveedor();
            Sucursal sucursal = ordenDeCompra.getIdSucursal();

            //Crear un nuevo DocumentoIngreso yey
            DocumentoIngreso documentoIngreso = new DocumentoIngreso();
            documentoIngreso.setFechaIngreso(fechaIngreso); //Aca ya habiamos dicho que era la fecha actual
            documentoIngreso.setSucursal(sucursal);
            documentoIngreso.setBodega(bodega);
            documentoIngreso.setProveedor(proveedor);
            documentoIngreso.setOrdenCompra(ordenDeCompra);

            //Persistimos el doc en la BD
            documentoIngresoRepository.save(documentoIngreso);

            //Guardamos en una collecion los productos y sus cantidades (de la orden de compra) 
            //Luego los vamos metiendo un array a medida que los vamos modificando como nos decia el enunciado
            Collection<ProductoPedido> productosPedido = productoPedidoRepository.obtenerProductosySuCantidadPorOrdenDeCompra(idOrdenCompra);
            
            //El array
            List<Map<String, Object>> productos = new ArrayList<>();

            //Iterar por cada producto pedido en la orden
            for (ProductoPedido productoPedido : productosPedido) {

                //Obtenemos el objeto "producto" que hace de producto en la llave primaria del producto pedido 
                Producto producto = productoPedido.getPk().getIdentificadorProducto();
               
                //Obtenemos la cantidad existente
                //Obtenemos el precio unitario (el costo en bodega)
                int cantidadIngresada = productoPedido.getCantidadEnOrden();
                double precioUnitario = producto.getCostoEnBodega();

                //Verificar si el producto ya está en la bodega o no, segun eso se crea o se suma la cantidad
                ProductoEnBodega productoEnBodega = productoEnBodegaRepository.findByProductoYBodega(producto.getIdentificador(), idBodega);

                //Con estas variables haremos calculos
                double nuevoCostoPromedio;
                int nuevaCantidadEnBodega;

                
                if (productoEnBodega != null) {
                    // Calcular el nuevo costo promedio (funcion de abajo)
                    nuevoCostoPromedio = calcularNuevoCostoPromedio(productoEnBodega.getCostoPromedio(), productoEnBodega.getCantidadEnBodega(), precioUnitario, cantidadIngresada);
                    // Calcular el nueva cantidad (sumar actual con nueva)
                    nuevaCantidadEnBodega = productoEnBodega.getCantidadEnBodega() + cantidadIngresada;

                    //Reemplazar valores para el producto
                    productoEnBodega.setCantidadEnBodega(nuevaCantidadEnBodega);
                    productoEnBodega.setCostoPromedio(nuevoCostoPromedio);
                } 
                else {
                    // Si el producto no estaba en la bodega, lo agregamos como nuevo
                    nuevoCostoPromedio = precioUnitario;
                    nuevaCantidadEnBodega = cantidadIngresada;
                    //El minimo de reorden y la capacidad de almacenar seran 1 por defecto
                    productoEnBodega = new ProductoEnBodega(producto, bodega, 1, nuevoCostoPromedio, 1, nuevaCantidadEnBodega);
                }

                //Persistir el producto modificado o creado en la bodega (en BD)
                productoEnBodegaRepository.save(productoEnBodega);

                //Preparamos la info del producto para añadir a la lista de productos de la rta
                Map<String, Object> productoDatos = new HashMap<>();
                productoDatos.put("identificador", producto.getIdentificador());
                productoDatos.put("nombre", producto.getNombre());
                productoDatos.put("precioUnitario", precioUnitario);
                productoDatos.put("cantidadIngresada", cantidadIngresada);
                productoDatos.put("nuevaCantidadEnBodega", nuevaCantidadEnBodega);
                productoDatos.put("costoPromedio", nuevoCostoPromedio);

                productos.add(productoDatos);
            }

            // Retornamos la respuesta completa si todo salió bien
            Map<String, Object> respuesta= new HashMap<>();
            respuesta.put("message", "Ingreso de productos registrado exitosamente");

            //Añadir los datos del encabezado
            respuesta.put("fechaIngreso", fechaIngreso);
            respuesta.put("sucursal", sucursal.getNombre()); 
            respuesta.put("bodega", bodega.getNombre()); 
            respuesta.put("proveedor", proveedor.getNombre());



            respuesta.put("productos", productos);
            return new ResponseEntity<>(respuesta, HttpStatus.OK);

        } catch (Exception e) {
            // Si ocurre una excepción, devolvemos solo el mensaje de error
            return new ResponseEntity<>("Error durante el ingreso de productos: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    //Calcular el nuevo costo promedio
    private Double calcularNuevoCostoPromedio(Double costoPromedioAnterior, Integer cantidadAnterior,
                                              Double precioUnitario, Integer cantidadIngresada) {
        return ((costoPromedioAnterior * cantidadAnterior) + (precioUnitario * cantidadIngresada)) / (cantidadAnterior + cantidadIngresada);
    }
}
